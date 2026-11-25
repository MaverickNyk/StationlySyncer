package com.stationly.backend.notices;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What the notices say about one stop.
 *
 * @param kind       closed, part_closed, exit_only, closing, or unclear (logged, never shown)
 * @param text       TfL's words, tidied
 * @param from       when this state starts (closing: when it will close), epoch ms
 * @param directions part_closed: what is closed. exit_only: the directions not stopping, if any
 * @param why        unclear: why
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StationStatus(String id, String name, String mode, String kind, String text, long from, long to,
                            List<String> directions, List<String> lines, String why) {

    public static final String CLOSED = "closed";
    public static final String PART_CLOSED = "part_closed";
    public static final String EXIT_ONLY = "exit_only";
    public static final String CLOSING = "closing";
    public static final String UNCLEAR = "unclear";

    boolean sameState(StationStatus o) {
        return o != null && kind.equals(o.kind) && directions.equals(o.directions) && lines.equals(o.lines);
    }

    boolean isUnclear() {
        return UNCLEAR.equals(kind);
    }

    StationStatus as(String newKind, String newWhy) {
        return new StationStatus(id, name, mode, newKind, text, from, to, directions, lines, newWhy);
    }
}
