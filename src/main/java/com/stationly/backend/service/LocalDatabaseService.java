package com.stationly.backend.service;

import com.stationly.backend.model.LineStatusResponse;
import java.util.List;

public interface LocalDatabaseService {
    void initialize();
    String getLastSyncTime(String collection);
    void updateLastSyncTime(String collection, String time);
    List<LineStatusResponse> getAllLineStatuses();
    void upsertLineStatus(LineStatusResponse status);
    void deleteLineStatus(String id);
}
