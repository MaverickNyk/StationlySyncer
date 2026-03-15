package com.stationly.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class FcmService implements NotificationService {

    @Value("${fcm.service-account-path}")
    private String serviceAccountPath;

    @Value("${firebase.database-url:}")
    private String databaseUrl;

    private final ObjectMapper objectMapper;
    private boolean fcmEnabled = false;

    // "Debounced" Queue: Holds the LATEST message for each topic.
    // If we get behind, we just overwrite the value, we don't backlog 2 cycles of
    // data.
    private final ConcurrentHashMap<String, Message> pendingMessages = new ConcurrentHashMap<>();

    // Dedicated Pacer Thread
    private final Thread pacerThread;
    private volatile boolean running = true;

    // Configuration
    // Target: ~1000 messages/sec (Max burst)
    // Tick: 500ms
    // Batch: 500 (Max allowed by FCM per batch)
    // Rationale: sending 50 small batches suffers from network latency overhead.
    // Bigger batches amortize the RTT.
    // If sendEach takes 500ms, we hit 500 msg/sec. If it takes 200ms, we hit 1000
    // msg/sec.
    private static final int PACING_INTERVAL_MS = 500;
    private static final int MESSAGES_PER_TICK = 500;

    public FcmService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // dedicated thread to avoid pool starvation and precise control
        this.pacerThread = new Thread(this::pacerLoop, "fcm-pacer");
        this.pacerThread.setDaemon(true);
    }

    @PostConstruct
    public void initialize() {
        if (serviceAccountPath != null && !serviceAccountPath.isEmpty()) {
            try {
                initializeFromFile(serviceAccountPath);
            } catch (Exception e) {
                // Fallback
            }
        }

        if (serviceAccountPath != null && !serviceAccountPath.isEmpty() && !fcmEnabled) {
            try (FileInputStream test = new FileInputStream(serviceAccountPath)) {
                initializeFromFile(serviceAccountPath);
            } catch (IOException e) {
                log.error("❌ Failed to read FCM Service Account file: {}", serviceAccountPath, e);
            }
        } else if (!fcmEnabled) {
            log.warn("⚠️  FCM service account not configured. FCM notifications will be disabled.");
        }

        if (fcmEnabled) {
            log.info("🚀 Starting FCM Pacer (Target: {} msg/sec, Debouncing Enabled)",
                    (1000 / PACING_INTERVAL_MS) * MESSAGES_PER_TICK);
            pacerThread.start();
        }
    }

    private void initializeFromFile(String path) {
        try {
            FileInputStream serviceAccount = new FileInputStream(path);
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .setDatabaseUrl(databaseUrl)
                    .setThreadManager(new BoundedThreadManager())
                    .build();

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
            }
            fcmEnabled = true;
            log.info("✅ Firebase Cloud Messaging initialized successfully (from file: {})", path);
        } catch (IOException e) {
            log.error("❌ Failed to initialize Firebase Cloud Messaging", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        try {
            pacerThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Dedicated Pacer Loop
     * Uses strict sleep to avoid bursts.
     */
    private void pacerLoop() {
        while (running) {
            long startTime = System.currentTimeMillis();
            try {
                processBatch();
            } catch (Exception e) {
                log.error("❌ FCM Pacer Error", e);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            long sleepTime = Math.max(0, PACING_INTERVAL_MS - elapsed);

            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    private void processBatch() {
        if (pendingMessages.isEmpty())
            return;

        List<Message> batch = new ArrayList<>(MESSAGES_PER_TICK);

        // ConcurrentHashMap iterator is weakly consistent - safe for concurrent mods
        Iterator<String> iterator = pendingMessages.keySet().iterator();

        while (iterator.hasNext() && batch.size() < MESSAGES_PER_TICK) {
            String topic = iterator.next();
            Message msg = pendingMessages.remove(topic); // Atomic remove
            if (msg != null) {
                batch.add(msg);
            }
        }

        if (!batch.isEmpty()) {
            try {
                log.info("📤 [FCM] Processing batch of {} messages (Queue before: {})", batch.size(), pendingMessages.size() + batch.size());
                BatchResponse response = FirebaseMessaging.getInstance().sendEach(batch);
                if (response.getFailureCount() > 0) {
                    log.warn("⚠️ [FCM] Batch completed with {} failures out of {}", response.getFailureCount(), batch.size());
                } else {
                    log.info("✅ [FCM] Batch sent successfully: {} messages", batch.size());
                }
                log.info("📊 [FCM] Queue after processing: {} items remaining", pendingMessages.size());
            } catch (Exception e) {
                log.error("❌ [FCM] Send error for batch of {}: {}", batch.size(), e.getMessage());
            }
        }
    }

    /**
     * Publish multiple messages to FCM topics (Batched by Pacer)
     */
    public void publishAll(Map<String, Object> topicPayloads) {
        if (!fcmEnabled || topicPayloads == null || topicPayloads.isEmpty()) {
            log.warn("⚠️ [FCM] publishAll called but FCM disabled or empty payload");
            return;
        }

        int added = 0;
        for (Map.Entry<String, Object> entry : topicPayloads.entrySet()) {
            try {
                String jsonPayload = objectMapper.writeValueAsString(entry.getValue());
                Message msg = Message.builder()
                        .setTopic(entry.getKey())
                        .putData("payload", jsonPayload)
                        .build();
                // Upsert: Replaces existing message for this topic if one was pending
                pendingMessages.put(entry.getKey(), msg);
                added++;
            } catch (Exception e) {
                log.error("❌ [FCM] Failed to build message for topic: {}", entry.getKey(), e);
            }
        }
        log.info("📥 [FCM] Added {} messages to queue. Total pending: {}", added, pendingMessages.size());
    }

    public void publishToTopic(String topic, Object payload) {
        if (!fcmEnabled)
            return;
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            Message message = Message.builder()
                    .setTopic(topic)
                    .putData("payload", jsonPayload)
                    .build();
            FirebaseMessaging.getInstance().send(message);
            log.debug("Successfully sent FCM message to topic: {}", topic);
        } catch (Exception e) {
            log.error("Failed to send FCM message to topic: {}", topic, e);
        }
    }

    public void sendClearSignal(String topic) {
        if (!fcmEnabled)
            return;
        try {
            Message message = Message.builder()
                    .setTopic(topic)
                    .putData("action", "CLEAR")
                    .build();
            FirebaseMessaging.getInstance().send(message);
            log.debug("Sent CLEAR signal to topic: {}", topic);
        } catch (Exception e) {
            log.error("Failed to send CLEAR signal to topic: {}", topic, e);
        }
    }

    private static class BoundedThreadManager extends com.google.firebase.ThreadManager {
        @Override
        protected java.util.concurrent.ExecutorService getExecutor(com.google.firebase.FirebaseApp app) {
            return java.util.concurrent.Executors.newFixedThreadPool(800, r -> {
                Thread t = new Thread(r);
                t.setName("firebase-bounded-" + t.getId());
                t.setDaemon(true);
                return t;
            });
        }

        @Override
        protected java.util.concurrent.ThreadFactory getThreadFactory() {
            return r -> {
                Thread t = new Thread(r);
                t.setName("firebase-daemon-" + t.getId());
                t.setDaemon(true);
                return t;
            };
        }

        @Override
        protected void releaseExecutor(com.google.firebase.FirebaseApp app,
                java.util.concurrent.ExecutorService executor) {
            executor.shutdown();
        }
    }
}
