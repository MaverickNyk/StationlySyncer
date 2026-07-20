package com.stationly.backend.service.predictionsources;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves which PredictionSource builds a station's lines this mode cycle.
 * Java mirror of stationly-backend's PredictionSourceFactory. Which stations
 * get board data at all is decided upstream by ArrivalDeparturesFetchService
 * (subscribed ∩ XR/OG, termini first under the per-cycle call cap); a station
 * skipped by that budget simply has no board entries in its context and lands
 * on the countdown source — i.e. exactly the pre-factory behavior.
 */
@Component
@RequiredArgsConstructor
public class PredictionSourceFactory {

    private final ElizabethOvergroundPredictionSource elizabethOvergroundPredictionSource;
    private final TubeDlrBusTramMixPredictionSource tubeDlrBusTramMixPredictionSource;

    public PredictionSource forStation(StationPredictionContext ctx) {
        if (elizabethOvergroundPredictionSource.supports(ctx)) {
            return elizabethOvergroundPredictionSource;
        }
        return tubeDlrBusTramMixPredictionSource; // universal fallback
    }
}
