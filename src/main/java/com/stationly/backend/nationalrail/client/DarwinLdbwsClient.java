package com.stationly.backend.nationalrail.client;

import com.stationly.backend.nationalrail.config.NationalRailProperties;
import com.stationly.backend.nationalrail.dto.DarwinServiceBoardRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SOAP client for Darwin's OpenLDBWS. This is the board SOURCE: when a Push
 * Port TS tells us a covered station changed, we pull that station's full,
 * self-contained board here and assemble the FCM payload from it.
 *
 * TODO: implement {@code GetArrDepBoardWithDetails} (SOAP envelope, ldbws-token
 * in the access header) once the token is issued, mapping response services to
 * {@link DarwinServiceBoardRow}. Add a rate limiter (mirror
 * {@code TflRateLimiter}) — OpenLDBWS has its own per-token quota, and drift
 * bursts + the heartbeat sweep both call this.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DarwinLdbwsClient {

    private final NationalRailProperties properties;

    /** Full live departure board for one CRS. */
    public List<DarwinServiceBoardRow> getDepartureBoard(String crs) {
        log.debug("🚆 [Darwin] OpenLDBWS board fetch for {}", crs);
        // TODO: SOAP GetArrDepBoardWithDetails(crs) → rows.
        return List.of();
    }
}
