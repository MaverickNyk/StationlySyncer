package com.stationly.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second dispatch path alongside FCM: posts changed stations to the Node
 * backend, which fans them out over WebSocket to foreground app clients.
 *
 * Why this exists: FCM keeps Android's widget alive with the app closed, but a
 * socket gives every platform sub-second updates while a board is on screen —
 * and on iOS it's the only thing that works at all, since silent APNs pushes
 * were proven undeliverable on-device (2026-07-30).
 *
 * STRICTLY ADDITIVE by construction. Three properties matter:
 *   1. {@link #publishAll} only touches a ConcurrentHashMap, so calling it from
 *      the poll thread costs a map insert. `@Scheduled` uses a single-threaded
 *      scheduler, so ANY blocking here would directly delay the next TfL poll.
 *   2. Dispatch happens on a dedicated daemon thread and never blocks — the
 *      WebClient call is subscribed, never `.block()`ed.
 *   3. Failures are logged and dropped, never retried into a backlog. If the
 *      backend is down, FCM and TfL polling carry on untouched.
 *
 * Deliberately does NOT implement {@link NotificationService}: LineService
 * injects that interface by type, and a second implementation would break its
 * constructor injection with NoUniqueBeanDefinitionException.
 *
 * Mirrors {@link FcmService}'s debounced-queue + pacer-thread shape, including
 * the upsert semantics — if the pacer falls behind, a station's newer payload
 * replaces the pending one rather than queueing both. Stale departure data is
 * worth less than nothing.
 */
@Service
@Slf4j
public class LiveStreamPublisher {

    @Value("${livestream.enabled:true}")
    private boolean enabled;

    @Value("${livestream.backend-url:http://127.0.0.1:3000}")
    private String backendUrl;

    @Value("${livestream.ingest-secret:}")
    private String sharedSecret;

    @Value("${livestream.timeout:2}")
    private int timeoutSeconds;

    /** Debounced queue: stationKey -> latest payload. Upsert, never backlog. */
    private final ConcurrentHashMap<String, Object> pending = new ConcurrentHashMap<>();

    private final WebClient.Builder webClientBuilder;
    private WebClient webClient;

    private final Thread pacerThread;
    private volatile boolean running = true;
    private volatile boolean active = false;

    /** Matches FcmService's cadence; the hub is local so this is generous. */
    private static final int PACING_INTERVAL_MS = 500;
    /** Cap per POST so one huge cycle can't build a multi-MB request body. */
    private static final int STATIONS_PER_TICK = 250;

    public LiveStreamPublisher(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
        this.pacerThread = new Thread(this::pacerLoop, "livestream-publisher");
        this.pacerThread.setDaemon(true);
    }

    @PostConstruct
    public void initialize() {
        if (!enabled) {
            log.info("ℹ️ Live-stream publisher disabled (livestream.enabled=false).");
            return;
        }
        if (sharedSecret == null || sharedSecret.isBlank()) {
            // Fail closed and loud: the backend rejects unauthenticated ingest
            // with 503, so without this we'd log a failure every 500ms forever.
            log.warn("⚠️ livestream.ingest-secret not set — live-stream publisher disabled.");
            return;
        }

        // .clone() is REQUIRED: WebClientConfig exposes a single shared
        // WebClient.Builder bean, so mutating it with defaultHeader would leak
        // our ingest secret onto every TfL API call made by TflApiClient.
        this.webClient = webClientBuilder.clone()
                .baseUrl(backendUrl)
                .defaultHeader("X-Internal-Secret", sharedSecret)
                .build();

        active = true;
        pacerThread.start();
        log.info("🔌 Live-stream publisher started (target: {})", backendUrl);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        try {
            pacerThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Enqueue changed stations for dispatch. Non-blocking by contract.
     *
     * @param stationPayloads the SAME map handed to {@code FcmService.publishAll}
     *                        — keys carry the {@code Station_} topic prefix; the
     *                        backend hub normalises them.
     */
    public void publishAll(Map<String, Object> stationPayloads) {
        if (!active || stationPayloads == null || stationPayloads.isEmpty()) return;
        for (Map.Entry<String, Object> entry : stationPayloads.entrySet()) {
            // Upsert: a newer payload for the same station replaces the pending one.
            pending.put(entry.getKey(), entry.getValue());
        }
    }

    private void pacerLoop() {
        while (running) {
            long start = System.currentTimeMillis();
            try {
                dispatchBatch();
            } catch (Exception e) {
                // Swallow everything — the loop must outlive any single failure.
                log.error("❌ [WS] Notifier error", e);
            }
            long sleep = Math.max(0, PACING_INTERVAL_MS - (System.currentTimeMillis() - start));
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    private void dispatchBatch() {
        if (pending.isEmpty()) return;

        Map<String, Object> batch = new HashMap<>();
        // Weakly-consistent iterator + atomic remove, same as FcmService.
        Iterator<String> it = pending.keySet().iterator();
        while (it.hasNext() && batch.size() < STATIONS_PER_TICK) {
            String key = it.next();
            Object payload = pending.remove(key);
            if (payload != null) batch.put(key, payload);
        }
        if (batch.isEmpty()) return;

        int count = batch.size();
        webClient.post()
                .uri("/internal/station-updates")
                .bodyValue(batch)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                // Fire-and-forget: never .block(), and never retry. A dropped
                // batch is superseded by the next poll cycle ~30s later, which
                // is far cheaper than backing up behind a wedged backend.
                .subscribe(
                        ok -> log.debug("✅ [WS] Delivered {} stations", count),
                        err -> log.warn("⚠️ [WS] Dispatch failed for {} stations: {}", count, err.getMessage())
                );
    }
}
