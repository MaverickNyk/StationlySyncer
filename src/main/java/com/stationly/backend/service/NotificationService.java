package com.stationly.backend.service;

public interface NotificationService {
    void publishAll(java.util.Map<String, Object> topicPayloads);

    /**
     * Queue a station notice for the stop's topic. The same topic as its departures,
     * but never in place of them: a message of its own, without `payload`, which is
     * what older apps look for and ignore it by. False when FCM is off.
     */
    default boolean publishStationStatus(String stationId, String json) {
        return false;
    }

    /** Stations whose last notice FCM would not take, since this was last asked. */
    default java.util.Set<String> failedStationStatuses() {
        return java.util.Set.of();
    }
}
