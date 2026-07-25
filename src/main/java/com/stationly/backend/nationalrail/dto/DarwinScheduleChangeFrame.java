package com.stationly.backend.nationalrail.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * A parsed Darwin Push Port {@code <schedule>} (SC) message — an intraday
 * change to a service itself, NOT just its timings: a cancellation, a
 * reinstatement, or a re-planned calling pattern. Applying only TS deltas and
 * ignoring these is the classic way a schedule mirror drifts wrong by mid-day
 * (cancelled trains keep showing, new services never appear).
 */
@Data
@Builder
public class DarwinScheduleChangeFrame {
    private String rid;
    private LocalDate serviceStartDate;
    private String toc;
    private boolean cancelled;
    private String destinationTiploc;
    /** Replacement calling pattern; empty when the message is a pure cancellation. */
    private List<DarwinScheduleCall> calls;
}
