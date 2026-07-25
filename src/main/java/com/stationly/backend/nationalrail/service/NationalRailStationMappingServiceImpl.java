package com.stationly.backend.nationalrail.service;

import com.stationly.backend.model.Station;
import com.stationly.backend.nationalrail.client.DarwinReferenceDataClient;
import com.stationly.backend.nationalrail.dto.DarwinLocationRef;
import com.stationly.backend.service.LocalDatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory identifier resolver (mirrors the {@code ChangeDetectionService}
 * cache idiom — zero per-message DB/network reads on the hot path). All three
 * indexes are rebuilt by {@link #refresh()} off a daily schedule.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NationalRailStationMappingServiceImpl implements NationalRailStationMappingService {

    /** Mode key under which National Rail stations are grouped in the shared Station store. */
    static final String MODE_NATIONAL_RAIL = "national-rail";

    private static final String NAPTAN_RAIL_PREFIX = "9100";
    private static final Pattern NAPTAN_RAIL_FORM = Pattern.compile("^9100([A-Z]{3})$");

    private final DarwinReferenceDataClient referenceDataClient;
    private final LocalDatabaseService localDatabaseService;

    private final Map<String, String> crsByTiploc = new ConcurrentHashMap<>();
    private final Map<String, String> nameByCrs = new ConcurrentHashMap<>();
    // Overrides for stations whose naptanId is NOT the pure 9100+CRS form.
    private final Map<String, String> crsByNaptanId = new ConcurrentHashMap<>();
    private final Map<String, String> naptanIdByCrs = new ConcurrentHashMap<>();

    @Override
    public Optional<String> crsForTiploc(String tiploc) {
        if (tiploc == null || tiploc.isBlank()) return Optional.empty();
        return Optional.ofNullable(crsByTiploc.get(tiploc.toUpperCase()));
    }

    @Override
    public Optional<String> crsForNaptanId(String naptanId) {
        if (naptanId == null || naptanId.isBlank()) return Optional.empty();
        String upper = naptanId.toUpperCase();
        Matcher matcher = NAPTAN_RAIL_FORM.matcher(upper);
        if (matcher.matches()) return Optional.of(matcher.group(1));
        return Optional.ofNullable(crsByNaptanId.get(upper));
    }

    @Override
    public Optional<String> naptanIdForCrs(String crs) {
        if (crs == null || crs.isBlank()) return Optional.empty();
        String upper = crs.toUpperCase();
        // An override (the real TfL naptanId for this CRS) wins over the derived
        // form, so pushes target the SAME topic the app subscribed to.
        String override = naptanIdByCrs.get(upper);
        if (override != null) return Optional.of(override);
        return Optional.of(NAPTAN_RAIL_PREFIX + upper);
    }

    @Override
    public Optional<String> naptanIdForTiploc(String tiploc) {
        return crsForTiploc(tiploc).flatMap(this::naptanIdForCrs);
    }

    @Override
    public Optional<String> nameForCrs(String crs) {
        if (crs == null || crs.isBlank()) return Optional.empty();
        return Optional.ofNullable(nameByCrs.get(crs.toUpperCase()));
    }

    @Override
    public void refresh() {
        refreshTiplocTable();
        refreshNaptanOverrides();
    }

    private void refreshTiplocTable() {
        Map<String, String> nextTiploc = new ConcurrentHashMap<>();
        Map<String, String> nextName = new ConcurrentHashMap<>();
        for (DarwinLocationRef ref : referenceDataClient.fetchLocationReference()) {
            if (ref.tiploc() == null || ref.crs() == null || ref.crs().isBlank()) continue;
            String crs = ref.crs().toUpperCase();
            nextTiploc.put(ref.tiploc().toUpperCase(), crs);
            if (ref.name() != null && !ref.name().isBlank()) nextName.putIfAbsent(crs, ref.name());
        }
        crsByTiploc.clear();
        crsByTiploc.putAll(nextTiploc);
        nameByCrs.clear();
        nameByCrs.putAll(nextName);
        log.info("NR_MAP: 🗺️ TIPLOC↔CRS table refreshed: {} location(s)", nextTiploc.size());
    }

    private void refreshNaptanOverrides() {
        Map<String, String> nextCrsByNaptan = new ConcurrentHashMap<>();
        Map<String, String> nextNaptanByCrs = new ConcurrentHashMap<>();
        for (Station station : localDatabaseService.getStationsByMode(MODE_NATIONAL_RAIL)) {
            String naptanId = station.getNaptanId();
            String crs = station.getCrs();
            if (naptanId == null || crs == null || crs.isBlank()) continue;
            String upperNaptan = naptanId.toUpperCase();
            String upperCrs = crs.toUpperCase();
            if (!NAPTAN_RAIL_FORM.matcher(upperNaptan).matches()) {
                nextCrsByNaptan.put(upperNaptan, upperCrs);
                nextNaptanByCrs.put(upperCrs, upperNaptan);
            }
        }
        crsByNaptanId.clear();
        crsByNaptanId.putAll(nextCrsByNaptan);
        naptanIdByCrs.clear();
        naptanIdByCrs.putAll(nextNaptanByCrs);
        log.info("NR_MAP: 🗺️ CRS↔naptanId override index refreshed: {} non-standard station(s)", nextNaptanByCrs.size());
    }
}
