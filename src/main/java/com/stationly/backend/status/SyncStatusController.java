package com.stationly.backend.status;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Status REST API consumed by stationly-admin's health dashboard.
 *
 * <ul>
 *   <li>{@code GET /health} — compact liveness (200, or 503 if down). Always open.</li>
 *   <li>{@code GET /sync-status} — full dashboard summary (latest + last-1h/24h + fcm/writer/retention).</li>
 *   <li>{@code GET /sync-status/runs?job=&before=&limit=} — recent raw per-cycle rows.</li>
 *   <li>{@code GET /sync-status/rollup?bucket=hour|day&job=&since=&until=} — historical aggregates.</li>
 * </ul>
 *
 * The {@code /sync-status*} endpoints are gated by {@link SyncStatusAuthFilter}
 * when {@code syncer.status.key} is set.
 */
@RestController
@RequiredArgsConstructor
public class SyncStatusController {

    private final SyncStatusService service;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = service.health();
        return ResponseEntity
                .status(service.isDown(body) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK)
                .body(body);
    }

    @GetMapping("/sync-status")
    public ResponseEntity<Map<String, Object>> syncStatus() {
        Map<String, Object> body = service.summary();
        return ResponseEntity
                .status(service.isDown(body) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK)
                .body(body);
    }

    @GetMapping("/sync-status/runs")
    public List<Map<String, Object>> runs(
            @RequestParam(required = false) String job,
            @RequestParam(defaultValue = "0") long before,
            @RequestParam(defaultValue = "50") int limit) {
        return service.runs(job, before, limit);
    }

    @GetMapping("/sync-status/rollup")
    public List<Map<String, Object>> rollup(
            @RequestParam(defaultValue = "hour") String bucket,
            @RequestParam(required = false) String job,
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "0") long until) {
        return service.rollup(bucket, job, since, until);
    }
}
