package com.stationly.backend.service;

public interface NotificationService {
    void publishAll(java.util.Map<String, Object> topicPayloads);
}
