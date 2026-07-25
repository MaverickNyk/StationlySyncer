package com.stationly.backend.nationalrail.service;

import com.stationly.backend.model.StationPredictions;

/**
 * Publishes an assembled National Rail board to its per-station FCM topic.
 * Thin wrapper over the shared {@code NotificationService} so National Rail
 * reuses the exact same FCM pacer/batcher and payload envelope the TfL path
 * uses — the app can't tell the two feeds apart, which is the whole point.
 */
public interface NationalRailPushNotifier {

    void push(StationPredictions board);
}
