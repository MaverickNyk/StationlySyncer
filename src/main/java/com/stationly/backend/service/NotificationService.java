package com.stationly.backend.service;

public interface NotificationService {
    void publishToTopic(String topic, Object payload);

    void publishAll(java.util.Map<String, Object> topicPayloads);
}
