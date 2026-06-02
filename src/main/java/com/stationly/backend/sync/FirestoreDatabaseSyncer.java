package com.stationly.backend.sync;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.ListenerRegistration;
import com.stationly.backend.service.LocalDatabaseService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Universal manager for syncing targeted Firebase metadata documents into local
 * RAM.
 * Simply add paths to `pathsToSync` and they will be instantly available via
 * `getDocument()`.
 */
@Component
@Slf4j
public class FirestoreDatabaseSyncer {

    private final Firestore firestore;
    private final LocalDatabaseService localDatabaseService;

    @org.springframework.beans.factory.annotation.Autowired
    public FirestoreDatabaseSyncer(
            @org.springframework.beans.factory.annotation.Autowired(required = false) Firestore firestore,
            LocalDatabaseService localDatabaseService) {
        this.firestore = firestore;
        this.localDatabaseService = localDatabaseService;
    }

    // Add any new collections/documents you want to keep synced here!
    private final List<String> pathsToSync = List.of(
            "metadata/subscribed_stations"
    // "metadata/premium_features",
    // "config/feature_flags"
    );

    // Thread-safe RAM cache of real-time document data
    private final Map<String, DocumentSnapshot> documentsCache = new ConcurrentHashMap<>();
    private final List<ListenerRegistration> activeListeners = new ArrayList<>();

    @PostConstruct
    public void startSyncing() {
        if (firestore == null) {
            log.warn("⚠️ Firestore not configured. FirestoreDatabaseSyncer will skip starting all listeners.");
            return;
        }

        log.info("🛰️ Initializing Real-time Sync for {} documents...", pathsToSync.size());

        for (String path : pathsToSync) {
            String[] parts = path.split("/");
            if (parts.length != 2) {
                log.error("❌ Invalid path format (must be collection/document): {}", path);
                continue;
            }

            String collection = parts[0];
            String documentId = parts[1];

            log.debug("🔗 Attaching listener to: {}", path);

            ListenerRegistration registration = firestore.collection(collection)
                    .document(documentId)
                    .addSnapshotListener((snapshot, error) -> {
                        if (error != null) {
                            log.error("❌ Syncer error on {}: {}", path, error.getMessage());
                            return;
                        }
                        if (snapshot != null && snapshot.exists()) {
                            documentsCache.put(path, snapshot);
                            log.debug("🔄 Synced updated document into RAM cache: {}", path);
                            persistIfSubscribedStations(path, snapshot);
                        } else {
                            log.warn("⚠️ Document {} missing or deleted from Firebase", path);
                            documentsCache.remove(path);
                            // Deliberately do NOT wipe the SQLite mirror on a
                            // transient remove — keeping the last-known set means
                            // we never accidentally miss every subscribed station.
                        }
                    });

            activeListeners.add(registration);
        }
    }

    /**
     * Get the real-time snapshot of the requested document path from local RAM.
     * @param path The path in format "collection/documentId"
     * @return The latest DocumentSnapshot, or null if deleted/missing.
     */
    public DocumentSnapshot getDocument(String path) {
        return documentsCache.get(path);
    }
    
    /**
     * Extremely efficient compute helper: Auto-converts the cached snapshot directly to a POJO class.
     * Use this to avoid writing expensive manual parsing logic every time you need the data!
     */
    public <T> T getDocumentAs(String path, Class<T> valueType) {
        DocumentSnapshot snapshot = documentsCache.get(path);
        if (snapshot != null && snapshot.exists()) {
            return snapshot.toObject(valueType);
        }
        return null;
    }

    /**
     * Mirror metadata/subscribed_stations into SQLite (and advance its sync
     * checkpoint) on every realtime update — the same master→slave pattern as
     * the other collections. RAM remains the primary read path; SQLite is the
     * durable cold-start copy so a restart never misses a subscribed station.
     */
    private void persistIfSubscribedStations(String path, DocumentSnapshot snapshot) {
        if (!"metadata/subscribed_stations".equals(path) || localDatabaseService == null) return;
        try {
            Map<String, Object> data = snapshot.getData();
            Object countsObj = (data != null) ? data.get("stationCounts") : null;
            if (!(countsObj instanceof Map)) return;
            @SuppressWarnings("unchecked")
            Map<String, Number> counts = (Map<String, Number>) countsObj;
            Set<String> active = counts.entrySet().stream()
                    .filter(e -> e.getValue() != null && e.getValue().intValue() > 0)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            localDatabaseService.replaceSubscribedStations(active);
            // Keep a checkpoint like every other synced collection.
            Object lut = (data != null) ? data.get("lastUpdatedTime") : null;
            String checkpoint = (lut != null) ? String.valueOf(lut) : String.valueOf(System.currentTimeMillis());
            localDatabaseService.updateLastSyncTime("subscribed_stations", checkpoint);
            log.debug("💾 Mirrored {} subscribed stations to SQLite (checkpoint {})", active.size(), checkpoint);
        } catch (Exception e) {
            log.error("❌ Failed to mirror subscribed_stations to SQLite", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("🛑 Shutting down {} Firestore document listeners", activeListeners.size());
        for (ListenerRegistration reg : activeListeners) {
            if (reg != null) {
                reg.remove();
            }
        }
        activeListeners.clear();
        documentsCache.clear();
    }
}
