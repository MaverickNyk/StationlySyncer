package com.stationly.backend.controller;

import com.stationly.backend.model.RefreshSummary;
import com.stationly.backend.model.LineStatusResponse;
import com.stationly.backend.service.LineService;
import com.stationly.backend.service.TflPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final TflPollingService tflPollingService;
    private final LineService lineService;

    @GetMapping("/refresh")
    public ResponseEntity<List<RefreshSummary>> refresh() {
        log.info("🔄 ADMIN: Manual refresh triggerred for all configured modes");
        List<RefreshSummary> summaries = tflPollingService.refreshAll();
        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/status/refresh")
    public ResponseEntity<List<LineStatusResponse>> refreshLineStatuses() {
        log.info("🔄 ADMIN: Manual line status refresh triggered");
        List<LineStatusResponse> statuses = lineService.syncLineStatuses();
        return ResponseEntity.ok(statuses);
    }
}
