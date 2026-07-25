package com.stationly.backend.nationalrail.policy;

import com.stationly.backend.nationalrail.dto.NationalRailBoardDeparture;
import org.springframework.stereotype.Component;

/**
 * The one place the {@code lineId} and {@code direction} keys for a National
 * Rail board are decided — because they are BACKEND-COUPLED, not free choices.
 *
 * ⚠️ The app matches {@code payload.lines[selection.line]} and
 * {@code lineData.dirs[selection.direction]} (case-insensitive); a key the
 * user's stored selection doesn't match renders an EMPTY board. So whatever
 * lineId/direction the backend assigns an NR station's selectable board, this
 * class must reproduce exactly.
 *
 * Defaults below are placeholders until NR station modeling is finalised in
 * stationly-backend (see module README pre-work):
 *   - single pseudo-line per station ("national-rail")
 *   - single direction bucket ("outbound")
 * Swap for per-TOC lines / destination-derived directions here ONLY, once the
 * backend contract is set — nothing else in the module hard-codes these.
 */
@Component
public class NationalRailBoardKeys {

    public static final String DEFAULT_LINE_ID = "national-rail";
    public static final String DEFAULT_LINE_NAME = "National Rail";
    public static final String DEFAULT_DIRECTION = "outbound";

    public String lineIdFor(NationalRailBoardDeparture departure) {
        // TODO(backend-coupling): per-TOC lines? then return departure.getOperatorCode().
        return DEFAULT_LINE_ID;
    }

    public String lineNameFor(NationalRailBoardDeparture departure) {
        return DEFAULT_LINE_NAME;
    }

    public String directionFor(NationalRailBoardDeparture departure) {
        // TODO(backend-coupling): destination-derived inbound/outbound bucketing.
        return DEFAULT_DIRECTION;
    }
}
