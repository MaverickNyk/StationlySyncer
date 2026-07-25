package com.stationly.backend.nationalrail.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coalesces a burst of drift triggers for the same station into ONE board
 * rebuild. One delayed train fires TS updates across all its downstream
 * stations, and a busy station is hit by many trains — without debouncing,
 * each would trigger its own OpenLDBWS fetch + push. A trailing timer per
 * station collapses a burst into a single refresh once it quiets.
 *
 * The actual (potentially slow, network-bound) refresh runs on a small worker
 * pool so the timer thread never blocks on OpenLDBWS.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NationalRailRefreshDebouncer {

    private final NationalRailStationRefreshService refreshService;

    @Value("${nationalrail.debounce.window-ms:4000}")
    private long debounceWindowMs;

    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "nr-debounce-timer"));
    private final java.util.concurrent.ExecutorService workers =
            Executors.newFixedThreadPool(4, r -> daemon(r, "nr-refresh-worker"));

    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    /** (Re)arm the trailing timer for a station; a fresh trigger resets the window. */
    public void schedule(String naptanId) {
        pending.compute(naptanId, (id, existing) -> {
            if (existing != null) existing.cancel(false);
            return timer.schedule(() -> fire(id), debounceWindowMs, TimeUnit.MILLISECONDS);
        });
    }

    private void fire(String naptanId) {
        pending.remove(naptanId);
        workers.submit(() -> {
            try {
                refreshService.refresh(naptanId);
            } catch (Exception e) {
                log.error("NR_DEBOUNCE: ❌ refresh failed for {}", naptanId, e);
            }
        });
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @PreDestroy
    public void shutdown() {
        timer.shutdownNow();
        workers.shutdown();
    }
}
