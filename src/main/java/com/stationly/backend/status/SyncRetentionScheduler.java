package com.stationly.backend.status;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Keeps the sync-run telemetry bounded. Because arrivals log a row every ~30s,
 * the raw table would grow ~2,880 rows/day; this sweep rolls aged rows up into
 * hour/day aggregates and deletes the originals so the DB stays small while
 * long-term trends survive.
 *
 * <p>Durations bind from {@code application.properties} (e.g. {@code 24h},
 * {@code 30d}) via Spring's relaxed {@link Duration} conversion.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncRetentionScheduler {

    private final SyncLogRepository repository;

    @Value("${syncer.status.raw-retention:24h}")
    private Duration rawRetention;
    @Value("${syncer.status.hourly-retention:30d}")
    private Duration hourlyRetention;
    @Value("${syncer.status.daily-retention:365d}")
    private Duration dailyRetention;

    /** Roll up + prune shortly after each hour (configurable cron). */
    @Scheduled(cron = "${syncer.status.rollup-cron:0 5 * * * *}")
    public void rollupAndPrune() {
        repository.rollupAndPrune(System.currentTimeMillis(),
                rawRetention.toMillis(), hourlyRetention.toMillis(), dailyRetention.toMillis());
    }

    /** Sweep once shortly after boot so a long downtime can't leave a backlog. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("SYNC-LOG: retention config — raw={}, hourly={}, daily={}",
                rawRetention, hourlyRetention, dailyRetention);
        rollupAndPrune();
    }

    public Duration getRawRetention() { return rawRetention; }
    public Duration getHourlyRetention() { return hourlyRetention; }
    public Duration getDailyRetention() { return dailyRetention; }
}
