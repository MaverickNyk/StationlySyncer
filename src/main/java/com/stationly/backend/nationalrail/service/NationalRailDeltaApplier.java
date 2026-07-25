package com.stationly.backend.nationalrail.service;

import com.stationly.backend.nationalrail.dto.DarwinScheduleCall;
import com.stationly.backend.nationalrail.dto.DarwinScheduleChangeFrame;
import com.stationly.backend.nationalrail.dto.DarwinTrainStatusFrame;
import com.stationly.backend.nationalrail.model.NationalRailCallingPointRow;
import com.stationly.backend.nationalrail.model.NationalRailScheduleRecord;
import com.stationly.backend.nationalrail.model.NationalRailServiceRow;
import com.stationly.backend.nationalrail.repository.NationalRailScheduleRepository;
import com.stationly.backend.nationalrail.util.NationalRailTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies live Push Port deltas to the SQL mirror and reports which COVERED
 * stations were impacted (so the listener can push just those). Handles TS
 * (timing/platform/cancel per calling point) and SC (whole-service
 * cancel/re-plan) — applying only TS and ignoring SC is how a mirror drifts
 * wrong by mid-day, so both are here.
 *
 * Covered-only: we mutate rows only for stations someone is watching, keeping
 * the national firehose's write load proportional to what we actually serve.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NationalRailDeltaApplier {

    private final NationalRailScheduleRepository repository;
    private final NationalRailStationMappingService stationMappingService;
    private final NationalRailCoverageService coverageService;

    /** @return covered naptanIds whose board changed. */
    public Set<String> applyTimingUpdate(DarwinTrainStatusFrame frame) {
        Set<String> impacted = new LinkedHashSet<>();
        if (frame == null || frame.getRid() == null || frame.getLocations() == null) return impacted;
        LocalDate ssd = frame.getServiceStartDate() != null ? frame.getServiceStartDate() : NationalRailTime.today();

        for (DarwinTrainStatusFrame.LocationUpdate loc : frame.getLocations()) {
            if (loc == null || !loc.carriesLiveChange()) continue;

            String naptanId = stationMappingService.naptanIdForTiploc(loc.getTiploc()).orElse(null);
            if (naptanId == null || !coverageService.isCovered(naptanId)) continue; // covered-only

            Long estMs = NationalRailTime.toEpochMs(loc.getDepartureEstimated(), ssd);
            Long actMs = NationalRailTime.toEpochMs(loc.getDepartureActual(), ssd);
            Boolean cancelled = loc.isCancelled() ? Boolean.TRUE : null; // never un-cancel via TS

            int rows = repository.applyTimingUpdate(frame.getRid(), loc.getTiploc(), estMs, actMs, loc.getPlatform(), cancelled);
            if (rows > 0) impacted.add(naptanId);
        }
        return impacted;
    }

    /** @return covered naptanIds whose board changed. */
    public Set<String> applyScheduleChange(DarwinScheduleChangeFrame frame) {
        if (frame == null || frame.getRid() == null) return Set.of();
        LocalDate ssd = frame.getServiceStartDate() != null ? frame.getServiceStartDate() : NationalRailTime.today();

        if (frame.getCalls() != null && !frame.getCalls().isEmpty()) {
            repository.applyScheduleChange(toRecord(frame, ssd));
        } else {
            // Pure cancellation / reinstatement.
            repository.applyServiceCancellation(frame.getRid(), frame.isCancelled());
        }

        // Impacted = every covered station this service calls at.
        Set<String> impacted = new LinkedHashSet<>();
        for (String crs : repository.callingCrsForRid(frame.getRid())) {
            stationMappingService.naptanIdForCrs(crs)
                    .filter(coverageService::isCovered)
                    .ifPresent(impacted::add);
        }
        return impacted;
    }

    private NationalRailScheduleRecord toRecord(DarwinScheduleChangeFrame frame, LocalDate ssd) {
        String destinationCrs = stationMappingService.crsForTiploc(frame.getDestinationTiploc()).orElse(null);
        String destinationName = destinationCrs != null
                ? stationMappingService.nameForCrs(destinationCrs).orElse(null) : null;

        NationalRailServiceRow service = NationalRailServiceRow.builder()
                .rid(frame.getRid()).serviceDate(ssd).toc(frame.getToc())
                .destinationCrs(destinationCrs).destinationName(destinationName)
                .cancelled(frame.isCancelled())
                .build();

        List<NationalRailCallingPointRow> callingPoints = new java.util.ArrayList<>();
        for (DarwinScheduleCall call : frame.getCalls()) {
            String crs = stationMappingService.crsForTiploc(call.getTiploc()).orElse(null);
            if (crs == null) continue;
            callingPoints.add(NationalRailCallingPointRow.builder()
                    .rid(frame.getRid()).tiploc(call.getTiploc()).crs(crs).serviceDate(ssd)
                    .scheduledDepartureMs(NationalRailTime.toEpochMs(call.getScheduledDeparture(), ssd))
                    .publicDeparture(call.isPublicDeparture()).cancelled(false)
                    .build());
        }
        return NationalRailScheduleRecord.builder().service(service).callingPoints(callingPoints).build();
    }
}
