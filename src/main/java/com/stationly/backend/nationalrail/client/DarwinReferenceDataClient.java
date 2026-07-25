package com.stationly.backend.nationalrail.client;

import com.stationly.backend.nationalrail.config.NationalRailProperties;
import com.stationly.backend.nationalrail.dto.DarwinLocationRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fetches Darwin's location reference data — the TIPLOC↔CRS table. Push Port
 * messages address stations by TIPLOC, so without this the listener can't tell
 * which of our covered stations a live update touched.
 *
 * TODO: implement the fetch+parse once reference-data credentials are issued.
 * The reference feed is a bulk file (a gzipped XML / JSON snapshot on the Rail
 * Data Marketplace); decode it here into location refs and keep the vendor
 * shape from leaking past this boundary. Refreshed daily (locations are stable).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DarwinReferenceDataClient {

    private final NationalRailProperties properties;

    public List<DarwinLocationRef> fetchLocationReference() {
        log.info("🗺️ [Darwin] Fetching location reference (TIPLOC↔CRS) from {}", properties.getReferenceDataUrl());
        // TODO: download + parse the reference snapshot into location refs.
        return List.of();
    }
}
