package com.stationly.backend.nationalrail.service;

import com.stationly.backend.model.StationPredictions;
import com.stationly.backend.nationalrail.util.NationalRailFcmTopic;
import com.stationly.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NationalRailPushNotifierImpl implements NationalRailPushNotifier {

    private final NotificationService notificationService;

    @Override
    public void push(StationPredictions board) {
        if (board == null || board.getStationId() == null) return;
        String topic = NationalRailFcmTopic.forNaptanId(board.getStationId());
        // Same envelope as the TfL path: publishAll JSON-serialises the value
        // into data["payload"], which the app parses as FcmPayload.
        notificationService.publishAll(Map.of(topic, board));
        log.info("NR_PUSH: 📡 board → {} ({} line(s))", topic,
                board.getLines() != null ? board.getLines().size() : 0);
    }
}
