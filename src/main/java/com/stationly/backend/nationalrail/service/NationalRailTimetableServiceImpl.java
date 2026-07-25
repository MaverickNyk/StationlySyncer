package com.stationly.backend.nationalrail.service;

import com.stationly.backend.nationalrail.client.DarwinTimetableFeedClient;
import com.stationly.backend.nationalrail.dto.DarwinScheduleCall;
import com.stationly.backend.nationalrail.dto.DarwinScheduleService;
import com.stationly.backend.nationalrail.model.NationalRailCallingPointRow;
import com.stationly.backend.nationalrail.model.NationalRailScheduleRecord;
import com.stationly.backend.nationalrail.model.NationalRailServiceRow;
import com.stationly.backend.nationalrail.repository.NationalRailScheduleRepository;
import com.stationly.backend.nationalrail.util.NationalRailTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns Darwin timetable-file services into SQL mirror rows. Resolves each
 * calling point's TIPLOC→CRS (skipping non-station timing points), converts
 * Darwin "HH:mm" to epoch millis, and rolls the day forward across a service
 * that runs past midnight (times within a journey are non-decreasing).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NationalRailTimetableServiceImpl implements NationalRailTimetableService {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final DarwinTimetableFeedClient timetableFeedClient;
    private final NationalRailStationMappingService stationMappingService;
    private final NationalRailScheduleRepository repository;

    @Override
    public void loadBaselineForToday() {
        LocalDate today = NationalRailTime.today();
        if (repository.hasBaselineForDate(today)) {
            log.info("NR_TT: ✅ Baseline for {} already loaded — skipping.", today);
        } else {
            reloadBaseline(today);
        }
        // Roll the day: yesterday's rid-scoped rows are dead once today is under way.
        repository.purgeBefore(today);
    }

    @Override
    public void reloadBaseline(LocalDate serviceDate) {
        long start = System.currentTimeMillis();
        // The TIPLOC↔CRS table must be current before we translate any calling point.
        stationMappingService.refresh();

        List<DarwinScheduleService> services = timetableFeedClient.fetchTimetable(serviceDate);
        List<NationalRailScheduleRecord> records = new ArrayList<>(services.size());
        for (DarwinScheduleService svc : services) {
            NationalRailScheduleRecord record = toRecord(svc, serviceDate);
            if (record != null) records.add(record);
        }

        repository.replaceBaselineForDate(serviceDate, records);
        log.info("NR_TT: 📅 Baseline {} — {} of {} service(s) ingested | {}ms",
                serviceDate, records.size(), services.size(), System.currentTimeMillis() - start);
    }

    private NationalRailScheduleRecord toRecord(DarwinScheduleService svc, LocalDate serviceDate) {
        if (svc.getCalls() == null || svc.getCalls().isEmpty()) return null;

        String destinationCrs = stationMappingService.crsForTiploc(svc.getDestinationTiploc()).orElse(null);
        String destinationName = destinationCrs != null
                ? stationMappingService.nameForCrs(destinationCrs).orElse(null) : null;

        NationalRailServiceRow service = NationalRailServiceRow.builder()
                .rid(svc.getRid())
                .serviceDate(serviceDate)
                .uid(svc.getUid())
                .toc(svc.getToc())
                .destinationCrs(destinationCrs)
                .destinationName(destinationName)
                .cancelled(false)
                .build();

        List<NationalRailCallingPointRow> callingPoints = new ArrayList<>();
        long dayOffset = 0;
        Long lastMs = null;
        for (DarwinScheduleCall call : svc.getCalls()) {
            // Monotonic-time day rollover: a call earlier than the previous one
            // has crossed midnight relative to the service start date.
            String anchor = call.getScheduledDeparture() != null ? call.getScheduledDeparture() : call.getScheduledArrival();
            Long anchorMs = NationalRailTime.toEpochMs(anchor, serviceDate);
            if (anchorMs != null) {
                anchorMs += dayOffset;
                if (lastMs != null && anchorMs < lastMs) { dayOffset += DAY_MS; anchorMs += DAY_MS; }
                lastMs = anchorMs;
            }

            String crs = stationMappingService.crsForTiploc(call.getTiploc()).orElse(null);
            if (crs == null) continue; // non-station timing point — never boardable

            Long depMs = NationalRailTime.toEpochMs(call.getScheduledDeparture(), serviceDate);
            if (depMs != null) depMs += dayOffset;

            callingPoints.add(NationalRailCallingPointRow.builder()
                    .rid(svc.getRid())
                    .tiploc(call.getTiploc())
                    .crs(crs)
                    .serviceDate(serviceDate)
                    .scheduledDepartureMs(depMs)
                    .estimatedDepartureMs(null)
                    .actualDepartureMs(null)
                    .platform(null)
                    .publicDeparture(call.isPublicDeparture())
                    .cancelled(false)
                    .build());
        }
        return NationalRailScheduleRecord.builder().service(service).callingPoints(callingPoints).build();
    }
}
