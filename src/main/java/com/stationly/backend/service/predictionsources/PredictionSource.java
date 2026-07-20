package com.stationly.backend.service.predictionsources;

import com.stationly.backend.model.LineData;

import java.util.Map;

/**
 * Strategy for turning one station's TfL data (for ONE mode cycle) into its
 * line predictions. Java mirror of stationly-backend's
 * src/services/predictionSources/PredictionSource.ts — the two codebases must
 * bucket and filter identically so REST responses and FCM payloads always
 * tell the same story.
 *
 * The pipeline around it is source-agnostic: the mode cycle fetches bulk
 * countdown arrivals (plus departure boards for the planned stations),
 * DataTransformationService picks ONE source per station via
 * PredictionSourceFactory, the source builds lineId → LineData, and the
 * transform wraps, prunes (4KB FCM cap) and publishes as before.
 */
public interface PredictionSource {

    /** Short name for logs, e.g. "tube-dlr-bus-tram-mix", "departure-board". */
    String name();

    /** Whether this source should build the lines for the given station. */
    boolean supports(StationPredictionContext ctx);

    /** Build lineId → LineData for the station within this mode cycle. */
    Map<String, LineData> buildLines(StationPredictionContext ctx);
}
