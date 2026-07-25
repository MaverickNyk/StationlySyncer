package com.stationly.backend.nationalrail.client;

import com.stationly.backend.nationalrail.config.NationalRailProperties;
import com.stationly.backend.nationalrail.dto.DarwinScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Fetches Darwin's daily Timetable file — the full-day, rid-keyed schedule
 * snapshot that seeds the SQL baseline every service every station will run
 * today. This is step 1 of the mirror: load once (≈02:30), then keep it
 * corrected from the Push Port.
 *
 * TODO: implement fetch + gunzip + XML parse once timetable-feed credentials
 * are issued. The file is a gzipped XML snapshot (a few MB compressed) with
 * one {@code <Journey rid=… ssd=…>} per service and its {@code <OR>/<IP>/<DT>}
 * calling points (tpl, public times, activity flags). Keep the vendor XML from
 * leaking past this boundary — emit {@link DarwinScheduleService}s only.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DarwinTimetableFeedClient {

    private final NationalRailProperties properties;

    public List<DarwinScheduleService> fetchTimetable(LocalDate serviceDate) {
        log.info("🗓️ [Darwin] Fetching daily timetable for {} from {}", serviceDate, properties.getTimetableFeedUrl());
        // TODO: download + gunzip + parse the national timetable snapshot.
        return List.of();
    }
}
