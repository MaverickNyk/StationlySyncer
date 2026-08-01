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
 * Second dispatch path alongside FCM: posts changed stations AND line statuses
 * to the Node backend, which fans them out over WebSocket to foreground app
 * clients.
 *
 * For line statuses this is not merely a second path — it is the ONLY one. The
 * backend used to learn of status changes through a Firestore onSnapshot
 * listener on `lineStatuses`; that listener was removed because it billed a
 * document read for every change, on every instance, forever. Without the
 * dispatch below the backend falls back to asking TfL itself, which is gated to
 * once per mode per 10 minutes — far too stale for a live stream.
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

    /**
     * Same shape, separate queue: lineId -> latest status.
     *
     * Kept apart from {@link #pending} rather than tagged in one map because the
     * two go to different endpoints and must not be able to contaminate each
     * other's batch — a line status arriving on /internal/station-updates would
     * be broadcast to station subscribers as a departure board.
     */
    private final ConcurrentHashMap<String, Object> pendingLines = new ConcurrentHashMap<>();

    private final WebClient.Builder webClientBuilder;
    private WebClient webClient;

    private final Thread pacerThread;
    private volatile boolean running = true;
    private volatile boolean active = false;

    /** Matches FcmService's cadence; the hub is local so this is generous. */
    private static final int PACING_INTERVAL_MS = 500;
    /** Cap per POST so one huge cycle can't build a multi-MB request body. */
    private static final int STATIONS_PER_TICK = 250;
    /**
     * Not the ~30 tube/rail lines you might assume — `bus` is in
     * tfl.transport.modes, so the real line count is ~700. A priming run marks
     * every one of them changed, which drains over ~7 ticks (3.5s). That is
     * fine, but it is why this cap exists rather than being decorative.
     */
    private static final int LINES_PER_TICK = 100;

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

    /**
     * Enqueue changed line statuses for dispatch. Non-blocking by contract,
     * same as {@link #publishAll}.
     *
     * @param statusesByLineId keyed by BARE lineId (e.g. {@code victoria}), not
     *                         by the {@code LineStatus_<mode>_<id>} FCM topic.
     *                         The backend hub tolerates the topic form too, but
     *                         sending bare ids keeps the wire self-describing
     *                         and avoids relying on prefix parsing.
     */
    public void publishLineStatuses(Map<String, Object> statusesByLineId) {
        if (!active || statusesByLineId == null || statusesByLineId.isEmpty()) return;
        for (Map.Entry<String, Object> entry : statusesByLineId.entrySet()) {
            pendingLines.put(entry.getKey(), entry.getValue());
        }
    }

    private void pacerLoop() {
        while (running) {
            long start = System.currentTimeMillis();
            try {
                dispatchBatch();
                dispatchLineBatch();
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
        Map<String, Object> batch = drain(pending, STATIONS_PER_TICK);
        if (batch.isEmpty()) return;
        post("/internal/station-updates", batch, "stations");
    }

    private void dispatchLineBatch() {
        Map<String, Object> batch = drain(pendingLines, LINES_PER_TICK);
        if (batch.isEmpty()) return;
        post("/internal/line-status-updates", batch, "line statuses");
    }

    /** Weakly-consistent iterator + atomic remove, same as FcmService. */
    private Map<String, Object> drain(ConcurrentHashMap<String, Object> queue, int cap) {
        Map<String, Object> batch = new HashMap<>();
        if (queue.isEmpty()) return batch;
        Iterator<String> it = queue.keySet().iterator();
        while (it.hasNext() && batch.size() < cap) {
            String key = it.next();
            Object payload = queue.remove(key);
            if (payload != null) batch.put(key, payload);
        }
        return batch;
    }

    private void post(String uri, Map<String, Object> batch, String label) {
        int count = batch.size();
        webClient.post()
                .uri(uri)
                .bodyValue(batch)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                // Fire-and-forget: never .block(), and never retry. A dropped
                // batch is superseded by the next poll cycle, which is far
                // cheaper than backing up behind a wedged backend.
                //
                // For line statuses a drop costs more than for stations: the
                // backend has no other live source, so the value stays stale
                // until the line changes again or its own 10-minute TfL
                // fallback fires. Still not worth a retry queue — the fallback
                // exists precisely to bound that.
                .subscribe(
                        ok -> log.debug("✅ [WS] Delivered {} {}", count, label),
                        err -> log.warn("⚠️ [WS] Dispatch failed for {} {}: {}", count, label, err.getMessage())
                );
    }
}
