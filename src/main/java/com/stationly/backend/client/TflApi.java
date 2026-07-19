package com.stationly.backend.client;

import com.stationly.backend.model.ArrivalDeparture;
import com.stationly.backend.model.ArrivalPrediction;
import java.util.List;
import java.util.Map;

public interface TflApi {
    List<ArrivalPrediction> getArrivalsByMode(String mode);

    /**
     * Rail-style live departure board for one station+line (Elizabeth line /
     * Overground only). Returns an empty list on any error — callers fall
     * back to the countdown arrivals for that line.
     */
    List<ArrivalDeparture> getArrivalDepartures(String naptanId, String lineId);

    List<Map<String, Object>> getLinesByMode(String mode);

    List<Map<String, Object>> getStopPointsByLine(String lineId);

    Map<String, Object> getRouteSequence(String lineId, String direction);

    List<Map<String, Object>> getLineStatuses(String modes);
}
