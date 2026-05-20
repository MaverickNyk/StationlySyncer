package com.stationly.backend.service;

import com.google.cloud.firestore.*;
import com.google.api.core.ApiFuture;
import com.stationly.backend.client.TflApi;
import com.stationly.backend.model.LineStatusResponse;
import com.stationly.backend.repository.DataRepository;
import com.stationly.backend.util.TflUtils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class LineService {

    private final TflApi tflApiClient;
    private final DataRepository<LineStatusResponse, String> lineStatusRepository;
    private final NotificationService fcmService;
    private final LocalDatabaseService localDatabaseService;
    private final Firestore firestore;

    @Value("${tfl.transport.modes}")
    private String tflTransportModes;

    private final Map<String, LineStatusResponse> lineStatusesCache = new ConcurrentHashMap<>();
    private ListenerRegistration listenerRegistration;

    @Autowired
    public LineService(TflApi tflApiClient,
                       DataRepository<LineStatusResponse, String> lineStatusRepository,
                       NotificationService fcmService,
                       LocalDatabaseService localDatabaseService,
                       @Autowired(required = false) Firestore firestore) {
        this.tflApiClient = tflApiClient;
        this.lineStatusRepository = lineStatusRepository;
        this.fcmService = fcmService;
        this.localDatabaseService = localDatabaseService;
        this.firestore = firestore;
    }

    @PostConstruct
    public void init() {
        log.info("CACHE: 📡 Initializing LineStatus cache...");

        // 1. Load from local database
        try {
            List<LineStatusResponse> localList = localDatabaseService.getAllLineStatuses();
            for (LineStatusResponse status : localList) {
                if (status != null && status.getId() != null) {
                    lineStatusesCache.put(status.getId(), status);
                }
            }
            log.info("CACHE: 📁 Load from SQLite success. Line Statuses: {}", lineStatusesCache.size());
        } catch (Exception e) {
            log.error("CACHE: ❌ Failed to load from local SQLite", e);
        }

        // 2. Perform Delta Sync and Register Firestore Listener
        if (firestore != null) {
            try {
                String lastSync = localDatabaseService.getLastSyncTime("lineStatuses");
                log.info("CACHE: 🔄 Delta sync [lineStatuses]. Last sync: {}", lastSync != null ? lastSync : "Never");

                Query query = firestore.collection("lineStatuses");
                if (lastSync != null && !lastSync.isEmpty()) {
                    query = query.whereGreaterThan("lastUpdatedTime", lastSync);
                }

                ApiFuture<QuerySnapshot> future = query.get();
                QuerySnapshot snapshot = future.get();
                if (!snapshot.isEmpty()) {
                    log.info("CACHE: 📥 Found {} new/modified documents in [lineStatuses]. Applying deltas...", snapshot.size());
                    String newestTime = lastSync != null ? lastSync : "";
                    for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
                        LineStatusResponse status = doc.toObject(LineStatusResponse.class);
                        if (status != null && status.getId() != null) {
                            lineStatusesCache.put(status.getId(), status);
                            localDatabaseService.upsertLineStatus(status);
                            if (status.getLastUpdatedTime() != null && status.getLastUpdatedTime().compareTo(newestTime) > 0) {
                                newestTime = status.getLastUpdatedTime();
                            }
                        }
                    }
                    if (!newestTime.isEmpty()) {
                        localDatabaseService.updateLastSyncTime("lineStatuses", newestTime);
                    }
                } else {
                    log.info("CACHE: 🏷️ Collection [lineStatuses] is already up to date.");
                }
            } catch (Exception e) {
                log.warn("CACHE: ⚠️ Firestore delta sync failed, continuing with local data. Error: {}", e.getMessage());
            }

            try {
                // Register real-time updates for changes after lastSyncTime
                String lastSync = localDatabaseService.getLastSyncTime("lineStatuses");
                if (lastSync == null || lastSync.isEmpty()) {
                    lastSync = "1970-01-01T00:00:00Z";
                }
                log.info("CACHE: ⚡ Setting up real-time listener for lineStatuses > {}", lastSync);

                final String initialSyncTime = lastSync;
                listenerRegistration = firestore.collection("lineStatuses")
                        .whereGreaterThan("lastUpdatedTime", initialSyncTime)
                        .addSnapshotListener((snapshots, e) -> {
                            if (e != null) {
                                log.error("CACHE: ❌ Listen failed", e);
                                return;
                            }
                            if (snapshots != null) {
                                String newestTime = initialSyncTime;
                                boolean hasNewest = false;
                                for (DocumentChange dc : snapshots.getDocumentChanges()) {
                                    QueryDocumentSnapshot doc = dc.getDocument();
                                    String id = doc.getId();
                                    switch (dc.getType()) {
                                        case ADDED:
                                        case MODIFIED:
                                            LineStatusResponse status = doc.toObject(LineStatusResponse.class);
                                            if (status != null && status.getId() != null) {
                                                LineStatusResponse existing = lineStatusesCache.get(status.getId());
                                                if (existing == null || status.getLastUpdatedTime() == null 
                                                        || existing.getLastUpdatedTime() == null
                                                        || status.getLastUpdatedTime().compareTo(existing.getLastUpdatedTime()) > 0) {
                                                    
                                                    log.info("CACHE: ⚡ Real-time update for line: {} ({} -> {})", 
                                                            id, 
                                                            existing != null ? existing.getLastUpdatedTime() : "none", 
                                                            status.getLastUpdatedTime());
                                                    
                                                    lineStatusesCache.put(id, status);
                                                    localDatabaseService.upsertLineStatus(status);
                                                    
                                                    if (status.getLastUpdatedTime() != null 
                                                            && status.getLastUpdatedTime().compareTo(newestTime) > 0) {
                                                        newestTime = status.getLastUpdatedTime();
                                                        hasNewest = true;
                                                    }
                                                }
                                            }
                                            break;
                                        case REMOVED:
                                            log.info("CACHE: ⚡ Real-time remove for line: {}", id);
                                            lineStatusesCache.remove(id);
                                            localDatabaseService.deleteLineStatus(id);
                                            break;
                                    }
                                }
                                if (hasNewest) {
                                    localDatabaseService.updateLastSyncTime("lineStatuses", newestTime);
                                    log.info("CACHE: 📝 Updated [lineStatuses] lastSyncTime in SQLite metadata to {}", newestTime);
                                }
                            }
                        });
            } catch (Exception e) {
                log.error("CACHE: ❌ Failed to register real-time Firestore listener", e);
            }
        } else {
            log.warn("CACHE: ⚠️ Firestore is null. Skipping Firestore sync and listener.");
        }
    }

    @PreDestroy
    public void cleanup() {
        if (listenerRegistration != null) {
            log.info("CACHE: Unregistering Firestore lineStatuses listener...");
            listenerRegistration.remove();
        }
    }

    /**
      * Poll Line Statuses from TfL API on the scheduled interval
      */
    public List<LineStatusResponse> syncLineStatuses() {
        log.info("╔═══════════════════════════════════════════════════════════════════");
        log.info("║ 🚇 LINE STATUS SYNC STARTED");
        log.info("╚═══════════════════════════════════════════════════════════════════");

        String[] modes = tflTransportModes.split(",");
        List<LineStatusResponse> allStatuses = new ArrayList<>();
        List<LineStatusResponse> toSave = new ArrayList<>();
        Map<String, Object> fcmUpdates = new HashMap<>();

        // 1. Fetch existing statuses to compare against
        log.info("📥 Step 1: Loading existing line statuses from Cache...");
        Map<String, LineStatusResponse> existingStatuses = new HashMap<>(lineStatusesCache);
        log.info("✅ Step 1: Loaded {} existing line statuses from Cache", existingStatuses.size());

        // 2. Process each mode
        log.info("📋 Step 2: Processing {} modes...", modes.length);
        for (String mode : modes) {
            String trimmedMode = mode.trim();
            if (trimmedMode.isEmpty())
                continue;

            log.info("   📡 [{}] Fetching line statuses...", trimmedMode);
            try {
                List<Map<String, Object>> rawStatuses = tflApiClient.getLineStatuses(trimmedMode);
                if (rawStatuses == null || rawStatuses.isEmpty()) {
                    log.warn("   ⚠️ [{}] No line statuses received", trimmedMode);
                    continue;
                }

                int changedCount = 0;
                for (Map<String, Object> raw : rawStatuses) {
                    LineStatusResponse newStatus = mapToLineStatusResponse(raw, trimmedMode);
                    LineStatusResponse oldStatus = existingStatuses.get(newStatus.getId());

                    // Change Detection
                    boolean changed = false;
                    if (oldStatus == null) {
                        changed = true; // New line
                    } else {
                        boolean statusChanged = !Objects.equals(oldStatus.getStatusSeverityDescription(),
                                newStatus.getStatusSeverityDescription());
                        
                        String newTflReason = newStatus.getReason(); // Raw TfL reason (null for Good Service)
                        String oldReason = oldStatus.getReason();
                        boolean reasonChanged = false;

                        if (newTflReason != null && !newTflReason.trim().isEmpty()) {
                            // TfL provided an explicit reason. Compare it with the old reason.
                            reasonChanged = !Objects.equals(oldReason, newTflReason);
                        } else {
                            // TfL reason is null/empty.
                            if ("Good Service".equalsIgnoreCase(newStatus.getStatusSeverityDescription())) {
                                if ("Good Service".equalsIgnoreCase(oldStatus.getStatusSeverityDescription())) {
                                    // Status remains Good Service, and new reason is null.
                                    // This is NOT a change. We keep the old reason we generated.
                                    newStatus.setReason(oldReason);
                                    reasonChanged = false;
                                } else {
                                    // Transitioning from delay to Good Service. This is a change.
                                    reasonChanged = true;
                                }
                            } else {
                                // For other severities (like delays) where reason is null, check if old reason was also null.
                                reasonChanged = (oldReason != null && !oldReason.trim().isEmpty());
                            }
                        }

                        changed = statusChanged || reasonChanged;
                    }

                    // If a change was detected and the status is Good Service and the reason is empty/null,
                    // generate a random Good Service message now!
                    if (changed && "Good Service".equalsIgnoreCase(newStatus.getStatusSeverityDescription())
                            && (newStatus.getReason() == null || newStatus.getReason().trim().isEmpty())) {
                        int index = new Random().nextInt(TflUtils.GOOD_SERVICE_MESSAGES.size());
                        newStatus.setReason(TflUtils.GOOD_SERVICE_MESSAGES.get(index));
                    }

                    if (changed) {
                        String topic = "LineStatus_" + newStatus.getMode() + "_" + newStatus.getId();
                        log.info("   🔔 [{}] Status changed: {} | Topic: {}",
                                        trimmedMode, newStatus.getId(), topic);
                        fcmUpdates.put(topic, newStatus);
                        toSave.add(newStatus);
                        changedCount++;
                    }

                    allStatuses.add(newStatus);
                }

                log.info("   ✅ [{}] Processed {} lines ({} changed)",
                                trimmedMode, rawStatuses.size(), changedCount);

            } catch (Exception e) {
                log.error("   ❌ [{}] Error polling line statuses: {}", trimmedMode, e.getMessage());
            }
        }

        // 3. Save to Firestore and update local cache/SQLite immediately to prevent polling race conditions
        if (!toSave.isEmpty()) {
            for (LineStatusResponse status : toSave) {
                lineStatusesCache.put(status.getId(), status);
                try {
                    localDatabaseService.upsertLineStatus(status);
                } catch (Exception ex) {
                    log.error("Failed to immediately save to local SQLite: {}", ex.getMessage());
                }
            }
            lineStatusRepository.saveAll(toSave);
            log.info("✅ Step 3: Saved {} line statuses to Firestore and updated local cache/SQLite", toSave.size());
        } else {
            log.info("✅ Step 3: No line status changes to save");
        }

        // 4. Publish to FCM
        if (!fcmUpdates.isEmpty()) {
            log.info("🚀 Step 4: Publishing {} line status updates to FCM...", fcmUpdates.size());
            fcmService.publishAll(fcmUpdates);
            log.info("✅ Step 4: Queued {} FCM messages", fcmUpdates.size());
        } else {
            log.info("✅ Step 4: No line status changes to publish");
        }

        log.info("╔═══════════════════════════════════════════════════════════════════");
        log.info("║ ✅ LINE STATUS SYNC COMPLETED | Total: {} | Changed: {}",
                        allStatuses.size(), fcmUpdates.size());
        log.info("╚═══════════════════════════════════════════════════════════════════");

        return allStatuses;
    }

    @SuppressWarnings("unchecked")
    private LineStatusResponse mapToLineStatusResponse(Map<String, Object> l, String mode) {
        String id = (String) l.get("id");
        String name = (String) l.get("name");
        List<Map<String, Object>> lineStatuses = (List<Map<String, Object>>) l.get("lineStatuses");

        String statusSeverityDescription = "Unknown";
        String reason = null;

        if (lineStatuses != null && !lineStatuses.isEmpty()) {
            Map<String, Object> selectedStatus = lineStatuses.get(0);
            int maxPriority = -1;
            for (Map<String, Object> status : lineStatuses) {
                Object sevObj = status.get("statusSeverity");
                if (sevObj != null) {
                    int severity;
                    if (sevObj instanceof Number) {
                        severity = ((Number) sevObj).intValue();
                    } else {
                        try {
                            severity = Integer.parseInt(sevObj.toString());
                        } catch (NumberFormatException e) {
                            continue; // skip if invalid
                        }
                    }
                    int priority = getSeverityPriority(severity);
                    if (priority > maxPriority) {
                        maxPriority = priority;
                        selectedStatus = status;
                    }
                }
            }
            statusSeverityDescription = (String) selectedStatus.get("statusSeverityDescription");
            reason = (String) selectedStatus.get("reason");
        }

        String utcTime = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());

        return LineStatusResponse.builder()
                .id(id)
                .name(name)
                .statusSeverityDescription(statusSeverityDescription)
                .reason(reason) // Store the raw reason from TfL
                .mode(mode)
                .lastUpdatedTime(utcTime)
                .build();
    }

    private int getSeverityPriority(int severity) {
        switch (severity) {
            case 1:  // Closed
            case 2:  // Suspended
            case 16: // Not Running
            case 20: // Service Closed / No Service
                return 9;
            case 4:  // Planned Closure
                return 8;
            case 3:  // Part Suspended
            case 5:  // Part Closure
            case 11: // Part Closed
                return 7;
            case 6:  // Severe Delays
                return 6;
            case 7:  // Reduced Service
            case 8:  // Bus Service
            case 15: // Diverted
                return 5;
            case 9:  // Minor Delays
            case 14: // Change of frequency
            case 17: // Issues Reported
                return 4;
            case 12: // Exit Only
            case 13: // No Step Free Access
            case 19: // Information
                return 2;
            case 0:  // Special Service
                return 1;
            case 10: // Good Service
            case 18: // No Issues
            default:
                return 0;
        }
    }

}

