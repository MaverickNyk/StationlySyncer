package com.stationly.backend.status;

import com.stationly.backend.service.FcmService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the read-side views for the status REST API and derives the overall
 * up/degraded/down health. Liveness rides the arrivals loop (every ~30s); the
 * slower line-status/station-sync loops are reported but don't gate liveness.
 */
@Service
@RequiredArgsConstructor
public class SyncStatusService {

    private final SyncLogRepository repository;
    private final SyncStatusRecorder recorder;
    private final SyncRetentionScheduler retention;
    private final FcmService fcmService;

    @Value("${tfl.polling.strategy:all}")
    private String pollingStrategy;
    @Value("${tfl.polling.interval:30000}")
    private long pollingIntervalMs;

    private final long startedAtMs = System.currentTimeMillis();

    private static final String UP = "up";
    private static final String DEGRADED = "degraded";
    private static final String DOWN = "down";

    /** Compact liveness for {@code /health} — derived from the latest arrivals run. */
    public Map<String, Object> health() {
        long now = System.currentTimeMillis();
        long uptime = now - startedAtMs;
        Map<String, Object> latest = repository.latestRun(SyncRunRecord.JOB_ARRIVALS);

        String status;
        String detail;
        Long lastAt = null;
        Long ageMs = null;
        Object cycle = null;
        boolean nap = false;

        if (latest == null) {
            if (uptime < 2 * pollingIntervalMs) {
                status = UP;
                detail = "warming up (no cycle completed yet)";
            } else {
                status = DOWN;
                detail = "no poll cycle since boot " + human(uptime) + " ago";
            }
        } else {
            lastAt = ((Number) latest.get("finishedAt")).longValue();
            ageMs = now - lastAt;
            cycle = latest.get("cycle");
            nap = Boolean.TRUE.equals(latest.get("napMode"));
            String runStatus = String.valueOf(latest.get("status"));

            if (ageMs > 3 * pollingIntervalMs) {
                status = DOWN;
                detail = "last arrivals cycle " + human(ageMs) + " ago (interval " + human(pollingIntervalMs) + ")";
            } else if (SyncRunRecord.FAILED.equals(runStatus) || SyncRunRecord.PARTIAL.equals(runStatus)) {
                status = DEGRADED;
                detail = "latest arrivals cycle " + runStatus;
            } else if ("subscribed".equalsIgnoreCase(pollingStrategy) && !fcmService.isFcmEnabled()) {
                status = DEGRADED;
                detail = "FCM disabled";
            } else {
                status = UP;
                detail = nap ? "idle (NAP: no subscribed stations)" : "cycling normally";
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("detail", detail);
        m.put("lastArrivalsAtMs", lastAt);
        m.put("lastArrivalsAgeMs", ageMs);
        m.put("cycle", cycle);
        m.put("napMode", nap);
        m.put("uptimeMs", uptime);
        m.put("nowMs", now);
        return m;
    }

    /** Full dashboard summary for {@code /sync-status}. */
    public Map<String, Object> summary() {
        long now = System.currentTimeMillis();
        long h1 = now - Duration.ofHours(1).toMillis();
        long h24 = now - Duration.ofHours(24).toMillis();

        Map<String, Object> latest = new LinkedHashMap<>();
        Map<String, Object> last1h = new LinkedHashMap<>();
        Map<String, Object> last24h = new LinkedHashMap<>();
        for (String job : new String[]{
                SyncRunRecord.JOB_ARRIVALS, SyncRunRecord.JOB_LINE_STATUS, SyncRunRecord.JOB_STATION_SYNC}) {
            String key = camel(job);
            latest.put(key, repository.latestRun(job));
            last1h.put(key, repository.aggregateSince(job, h1));
            last24h.put(key, repository.aggregateSince(job, h24));
        }

        Map<String, Object> fcm = new LinkedHashMap<>();
        fcm.put("enabled", fcmService.isFcmEnabled());
        fcm.put("pending", fcmService.getPendingCount());
        fcm.put("totalSent", fcmService.getTotalSent());
        fcm.put("totalFailed", fcmService.getTotalFailed());
        fcm.put("lastBatchSent", fcmService.getLastBatchSent());
        fcm.put("lastBatchFailed", fcmService.getLastBatchFailed());
        long lastBatchAt = fcmService.getLastBatchAtMs();
        fcm.put("lastBatchAtMs", lastBatchAt == 0 ? null : lastBatchAt);

        Map<String, Object> writer = new LinkedHashMap<>();
        writer.put("queueDepth", recorder.queueDepth());
        writer.put("dropped", recorder.droppedCount());
        writer.put("written", recorder.writtenCount());

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("rawRetentionMs", retention.getRawRetention().toMillis());
        ret.put("hourlyRetentionMs", retention.getHourlyRetention().toMillis());
        ret.put("dailyRetentionMs", retention.getDailyRetention().toMillis());
        ret.put("rawRows", repository.count("sync_run"));
        ret.put("rollupRows", repository.count("sync_run_rollup"));

        Map<String, Object> m = new LinkedHashMap<>();
        m.putAll(health());
        m.put("startedAtMs", startedAtMs);
        m.put("pollingStrategy", pollingStrategy);
        m.put("pollingIntervalMs", pollingIntervalMs);
        m.put("latest", latest);
        m.put("last1h", last1h);
        m.put("last24h", last24h);
        m.put("fcm", fcm);
        m.put("writer", writer);
        m.put("retention", ret);
        return m;
    }

    public List<Map<String, Object>> runs(String job, long before, int limit) {
        return repository.recentRuns(job, before, Math.min(Math.max(limit, 1), 500));
    }

    public List<Map<String, Object>> rollup(String bucket, String job, long since, long until) {
        String b = "day".equalsIgnoreCase(bucket) ? "day" : "hour";
        return repository.rollups(b, job, since, until);
    }

    public boolean isDown(Map<String, Object> view) {
        return DOWN.equals(view.get("status"));
    }

    private static String camel(String job) {
        switch (job) {
            case SyncRunRecord.JOB_LINE_STATUS:  return "lineStatus";
            case SyncRunRecord.JOB_STATION_SYNC: return "stationSync";
            default:                             return "arrivals";
        }
    }

    private static String human(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        return (h / 24) + "d";
    }
}
