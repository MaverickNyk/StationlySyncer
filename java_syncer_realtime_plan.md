# Task: Implement Real-time Registry Listener in StationlySyncer

## Overview
Instead of polling the Node.js API, the `StationlySyncer` (Java) should implement its own real-time listener on the Firestore registry document. This allows the syncer to react instantly to new user subscriptions without additional API overhead.

## Firestore Registry Path
- **Collection**: `metadata`
- **Document**: `subscribed_stations`
- **Target Field**: `stationCounts` (Map<String, Long>)

## Implementation Steps (Java/Spring)

### 1. Dependency Requirement
Ensure the `google-cloud-firestore` library is in your `pom.xml` or `build.gradle`.

### 2. Create the Registry Listener Service
Create a service that manages the in-memory cache of station IDs.

```java
@Service
public class StationRegistryService {
    // Thread-safe set of active Naptan IDs
    private final Set<String> subscribedStationIds = ConcurrentHashMap.newKeySet();
    private final Firestore db;

    public StationRegistryService(Firestore db) {
        this.db = db;
        startRegistryListener();
    }

    private void startRegistryListener() {
        DocumentReference docRef = db.collection("metadata").document("subscribed_stations");
        
        docRef.addSnapshotListener((snapshot, e) -> {
            if (e != null) {
                System.err.println("Registry Listener failed: " + e.getMessage());
                return;
            }

            if (snapshot != null && snapshot.exists()) {
                Map<String, Object> data = snapshot.getData();
                if (data != null && data.containsKey("stationCounts")) {
                    Map<String, Long> counts = (Map<String, Long>) data.get("stationCounts");
                    
                    // Update in-memory IDs
                    subscribedStationIds.clear();
                    subscribedStationIds.addAll(counts.keySet());
                    
                    System.out.println("🔄 Syncer Registry Updated. Active stations: " + subscribedStationIds.size());
                }
            }
        });
    }

    public boolean isSubscribed(String naptanId) {
        return subscribedStationIds.contains(naptanId);
    }

    public Set<String> getActiveIds() {
        return Collections.unmodifiableSet(subscribedStationIds);
    }
}
```

### 3. Integrate with Sync Loop
Update the main syncing logic to use this service as a filter.

```java
public void syncAllArrivals() {
    // Only iterate through stations that actually have users
    Set<String> toSync = registryService.getActiveIds();
    
    for (String naptanId : toSync) {
        // 1. Fetch from TfL
        // 2. Update Firestore Predictions
        // 3. Log completion
    }
}
```

## Benefits
- **Zero Latency**: The syncer knows a user joined *the second* they hit "Subscribe" in the app.
- **Zero API Overheads**: No need for the Node.js `subscribed-ids` endpoint for the syncer.
- **Cost Effective**: Firestore listeners (onSnapshot) are charged as single reads on change, staying well within the free tier.

## Recommendation
This service should be initialized on startup (`@PostConstruct` or constructor) so the first sync cycle already has the data.
