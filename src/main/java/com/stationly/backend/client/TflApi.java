package com.stationly.backend.client;

import com.stationly.backend.model.ArrivalPrediction;
import java.util.List;
import java.util.Map;

public interface TflApi {
    List<ArrivalPrediction> getArrivalsByMode(String mode);

    List<Map<String, Object>> getLinesByMode(String mode);

    List<Map<String, Object>> getStopPointsByLine(String lineId);

    Map<String, Object> getRouteSequence(String lineId, String direction);

    List<Map<String, Object>> getLineStatuses(String modes);
}
