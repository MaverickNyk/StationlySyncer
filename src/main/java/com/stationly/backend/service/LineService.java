package com.stationly.backend.service;

// No Firestore imports: line statuses are memory-only. See syncLineStatuses.
import com.stationly.backend.client.TflApi;
import com.stationly.backend.model.LineStatusResponse;
import com.stationly.backend.util.TimeUtils;
import com.stationly.backend.util.TflUtils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class LineService {

    private final TflApi tflApiClient;
    private final NotificationService fcmService;
    /**
     * Injected by CONCRETE type, not via NotificationService. LiveStreamPublisher
     * deliberately does not implement that interface — a second implementation
     * would break fcmService's by-type injection with
     * NoUniqueBeanDefinitionException.
     */
    private final LiveStreamPublisher liveStreamPublisher;

    @Value("${tfl.transport.modes}")
    private String tflTransportModes;

    /**
     * THE store for line statuses. Nothing is persisted any more — Firestore and
     * SQLite were both removed once the backend stopped reading them, leaving
     * the Syncer as the only reader of its own writes.
     *
     * Consequence: this is also the only change-detection baseline, so a cold
     * start sees every line as new. See {@link #primed}.
     */
    private final Map<String, LineStatusResponse> lineStatusesCache = new ConcurrentHashMap<>();

    /**
     * False until the first sync completes. Guards the FCM push ONLY: with no
     * persisted baseline, the first poll after every restart or deploy marks all
     * ~30 lines as changed, which would notify every subscribed user that
     * nothing happened. The stream push is not suppressed — it is idempotent and
     * the backend needs current state.
     */
    private volatile boolean primed = false;

    @Autowired
    public LineService(TflApi tflApiClient,
                       NotificationService fcmService,
                       LiveStreamPublisher liveStreamPublisher) {
        this.tflApiClient = tflApiClient;
        this.fcmService = fcmService;
        this.liveStreamPublisher = liveStreamPublisher;
    }

    // init()/cleanup() removed with the Firestore delta sync and onSnapshot
    // listener. Both existed to pick up line statuses written by the Node
    // backend's tier-4 refresh; that write is gone, so the listener was reading
    // back only this service's own writes — pure Firestore cost for no data.
    // The cache now warms on the first scheduled poll (see `primed`).

    public List<LineStatusResponse> syncLineStatuses() {
        log.info("╔═══════════════════════════════════════════════════════════════════");
        log.info("║ 🚇 LINE STATUS SYNC STARTED");
        log.info("╚═══════════════════════════════════════════════════════════════════");

        String[] modes = tflTransportModes.split(",");
        List<LineStatusResponse> allStatuses = new ArrayList<>();
        List<LineStatusResponse> toSave = new ArrayList<>();
        Map<String, Object> fcmUpdates = new HashMap<>();
        // Same changed statuses, keyed by BARE lineId for the WebSocket ingest.
        // Kept separate from fcmUpdates because that map is keyed by FCM topic
        // (LineStatus_<mode>_<id>) and the backend wants plain ids.
        Map<String, Object> streamUpdates = new HashMap<>();

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
                        streamUpdates.put(newStatus.getId(), newStatus);
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

        // 3. Update the in-memory cache — the only store there is.
        //
        // The Firestore saveAll and the SQLite upsert were both removed. The
        // backend no longer reads either collection; it is fed directly by the
        // POST in step 5, which is a single local HTTP call instead of a
        // document write plus a replicated read on every instance.
        if (!toSave.isEmpty()) {
            for (LineStatusResponse status : toSave) {
                lineStatusesCache.put(status.getId(), status);
            }
            log.info("✅ Step 3: Cached {} changed line statuses", toSave.size());
        } else {
            log.info("✅ Step 3: No line status changes to cache");
        }

        // 4. Publish to FCM — skipped on the first sync after boot.
        //
        // Nothing is persisted, so the baseline starts empty and EVERY line
        // reads as changed on a cold start. Without this guard each restart or
        // deploy would push ~30 "status changed" notifications to every
        // subscribed user for changes that never happened.
        if (!primed) {
            log.info("✅ Step 4: First sync since boot — baseline primed with {} lines, FCM suppressed",
                            allStatuses.size());
        } else if (!fcmUpdates.isEmpty()) {
            log.info("🚀 Step 4: Publishing {} line status updates to FCM...", fcmUpdates.size());
            fcmService.publishAll(fcmUpdates);
            log.info("✅ Step 4: Queued {} FCM messages", fcmUpdates.size());
        } else {
            log.info("✅ Step 4: No line status changes to publish");
        }

        // 5. Publish to the WebSocket stream.
        //
        // This is the backend's ONLY live source of line status: its Firestore
        // onSnapshot listener was removed to stop paying a document read per
        // change. Without this dispatch it falls back to polling TfL once per
        // mode per 10 minutes, which is far too stale to call a stream live.
        //
        // Non-blocking and failure-tolerant by contract — see
        // LiveStreamPublisher — so it cannot delay or break this sync.
        //
        // Deliberately NOT suppressed on the priming run, unlike FCM: the
        // backend's cache is empty at that point and needs current state, and a
        // stream update only refreshes what a client already displays rather
        // than interrupting the user.
        if (!streamUpdates.isEmpty()) {
            liveStreamPublisher.publishLineStatuses(streamUpdates);
            log.info("✅ Step 5: Queued {} line status updates for the live stream", streamUpdates.size());
        }

        // Only once a baseline actually exists. If TfL was unreachable for every
        // mode this run, allStatuses is empty and the cache is still cold —
        // marking it primed would let the NEXT healthy sync treat all ~30 lines
        // as new and fire the very FCM storm this flag exists to prevent.
        if (!allStatuses.isEmpty()) {
            primed = true;
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

        return LineStatusResponse.builder()
                .id(id)
                .name(name)
                .statusSeverityDescription(statusSeverityDescription)
                .reason(reason) // Store the raw reason from TfL
                .mode(mode)
                .lastUpdatedTime(TimeUtils.nowMs()) // epoch millis (was ISO string)
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

