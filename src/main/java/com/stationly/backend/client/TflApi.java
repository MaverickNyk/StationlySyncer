package com.stationly.backend.client;

import java.util.List;
import java.util.Map;

public interface TflApi {
    List<Map<String, Object>> getLinesByMode(String mode);

    Map<String, Object> getLineRoute(String lineId);

    List<Map<String, Object>> getLineStatuses(String modes);
}
