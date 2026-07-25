package com.stationly.backend.nationalrail.service;

import com.stationly.backend.model.LineData;
import com.stationly.backend.model.StationPredictions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative push gate: content-diffs a freshly-assembled board against the
 * last one we pushed for that station, and also forces a push when the board
 * has gone unrefreshed past the heartbeat window. In-memory only (mirrors
 * {@code ChangeDetectionService}); state loss on restart just means one
 * redundant push per station, self-corrects immediately.
 *
 * Because this diffs the ASSEMBLED board (not the raw Darwin delta), it is
 * correct even if the upstream TS materiality pre-filter is imperfect — only a
 * genuinely different board is pushed.
 */
@Service
@Slf4j
public class NationalRailBoardChangeDetector {

    private final Map<String, Map<String, LineData>> lastPushedLines = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPushedAtMs = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * @return true if {@code board} should be pushed (content changed, or the
     *         heartbeat window elapsed). Updates the watermark when it returns true.
     */
    public boolean shouldPush(String naptanId, StationPredictions board, long heartbeatMs) {
        Object lock = locks.computeIfAbsent(naptanId, k -> new Object());
        synchronized (lock) {
            Map<String, LineData> current = board.getLines();
            Map<String, LineData> previous = lastPushedLines.get(naptanId);
            long now = System.currentTimeMillis();
            long last = lastPushedAtMs.getOrDefault(naptanId, 0L);

            boolean contentChanged = previous == null || !previous.equals(current);
            boolean heartbeatDue = now - last >= heartbeatMs;

            if (!contentChanged && !heartbeatDue) return false;

            lastPushedLines.put(naptanId, current != null ? new HashMap<>(current) : new HashMap<>());
            lastPushedAtMs.put(naptanId, now);
            if (!contentChanged) {
                log.debug("NR_DIFF: 💓 heartbeat push for {}", naptanId);
            }
            return true;
        }
    }

    /** Forget a station (e.g. it dropped out of coverage) so its watermark can't go stale. */
    public void forget(String naptanId) {
        lastPushedLines.remove(naptanId);
        lastPushedAtMs.remove(naptanId);
        locks.remove(naptanId);
    }
}
