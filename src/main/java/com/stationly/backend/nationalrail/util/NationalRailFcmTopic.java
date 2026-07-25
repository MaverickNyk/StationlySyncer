package com.stationly.backend.nationalrail.util;

/**
 * Builds the per-station FCM topic name.
 *
 * MUST stay byte-for-byte identical to the TfL path's topic format
 * ({@code DataTransformationService.normalize} + {@code "Station_"} prefix) —
 * the app subscribes ONE topic per station ({@code Station_<naptanId>}) and
 * renders whatever board arrives there, regardless of which feed produced it.
 * National Rail pushes therefore have to land on that exact topic.
 */
public final class NationalRailFcmTopic {

    private static final String STATION_PREFIX = "Station_";

    private NationalRailFcmTopic() {}

    public static String forNaptanId(String naptanId) {
        return STATION_PREFIX + normalize(naptanId);
    }

    private static String normalize(String input) {
        if (input == null) return "";
        return input.toUpperCase().replaceAll("[^A-Z0-9-_.~%]", "~");
    }
}
