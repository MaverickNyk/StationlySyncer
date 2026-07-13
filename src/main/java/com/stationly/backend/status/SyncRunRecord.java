package com.stationly.backend.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * One immutable record of a single sync run, handed to {@link SyncStatusRecorder}
 * for async persistence into the {@code sync_run} table.
 *
 * <p>Built cheaply on the sync thread but NEVER written there — the recorder's
 * background daemon does all the SQLite I/O, so logging a run can never slow down
 * (or fail) the actual sync.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncRunRecord {

    /** {@link #JOB_ARRIVALS} | {@link #JOB_LINE_STATUS} | {@link #JOB_STATION_SYNC} */
    private String jobType;

    private long startedAt;   // epoch ms
    private long finishedAt;  // epoch ms
    private long durationMs;

    /** {@link #OK} | {@link #PARTIAL} | {@link #FAILED} | {@link #NAP} */
    private String status;

    private Integer cycle;                 // arrivals: monotonic cycle number
    private boolean napMode;               // arrivals: no subscribed stations (healthy idle)
    private Integer activeSubscriptions;   // arrivals: subscribed-station count this cycle
    private Integer modesProcessed;

    private Integer arrivals;       // arrivals: total predictions processed (post-filter)
    private Integer stationGroups;  // arrivals: grouped stations
    private Integer fcmQueued;      // arrivals/line: FCM messages queued

    private Integer totalLines;     // line_status: lines seen
    private Integer changed;        // line_status: changed lines

    private Integer errors;         // count of sub-failures (per-mode / per-line)
    private String errorMsg;        // top-level failure message, if any

    /** Rich per-run breakdown serialized to {@code detail_json} (e.g. per-mode map). */
    private Map<String, Object> detail;

    // ---- status values ----
    public static final String OK = "ok";
    public static final String PARTIAL = "partial";
    public static final String FAILED = "failed";
    public static final String NAP = "nap";

    // ---- job types ----
    public static final String JOB_ARRIVALS = "arrivals";
    public static final String JOB_LINE_STATUS = "line_status";
    public static final String JOB_STATION_SYNC = "station_sync";
}
