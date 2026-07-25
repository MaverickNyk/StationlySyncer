package com.stationly.backend.nationalrail.service;

import java.util.Optional;

/**
 * The identifier-translation seam for the module. Three code spaces meet here:
 *   - TIPLOC  — how the Push Port addresses locations
 *   - CRS     — how OpenLDBWS addresses stations
 *   - naptanId — how the rest of the (multi-mode) app addresses everything
 *
 * Chain: {@code TIPLOC → CRS → naptanId}. TIPLOC→CRS comes from Darwin
 * reference data; CRS↔naptan is derivation-first (`9100`+CRS) with an in-memory
 * override index for TfL `910G…` hub forms sourced from {@code Station.crs}.
 */
public interface NationalRailStationMappingService {

    Optional<String> crsForTiploc(String tiploc);

    Optional<String> crsForNaptanId(String naptanId);

    Optional<String> naptanIdForCrs(String crs);

    /** Convenience for the Push Port path: TIPLOC → CRS → naptanId in one hop. */
    Optional<String> naptanIdForTiploc(String tiploc);

    /** Public station name for a CRS (from reference data) — for destination display. */
    Optional<String> nameForCrs(String crs);

    /** Rebuild the TIPLOC↔CRS table (from reference data) and the CRS↔naptan overrides. */
    void refresh();
}
