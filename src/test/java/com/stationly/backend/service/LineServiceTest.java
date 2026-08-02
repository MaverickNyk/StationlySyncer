package com.stationly.backend.service;

import com.stationly.backend.client.TflApi;
import com.stationly.backend.model.LineStatusResponse;
import com.stationly.backend.util.TflUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Line statuses are no longer persisted to Firestore or SQLite, so there is no
 * repository or local-db seam left to seed "existing state" through. Tests that
 * need a baseline establish it the way production does: run one sync to prime
 * the in-memory cache, then assert on the second.
 */
@ExtendWith(MockitoExtension.class)
class LineServiceTest {

    @Mock
    private TflApi tflApiClient;
    @Mock
    private NotificationService fcmService;

    /**
     * Hand-rolled double, NOT @Mock. LiveStreamPublisher is a concrete class and
     * Mockito's inline mock maker cannot instrument it on this JDK ("Could not
     * modify all classes"), which fails every test in this class. Subclassing is
     * enough: the method under test is public and non-final, and the constructor
     * only stores its argument and creates an unstarted daemon thread, so a null
     * builder is safe.
     */
    private static class RecordingPublisher extends LiveStreamPublisher {
        final List<Map<String, Object>> lineBatches = new ArrayList<>();

        RecordingPublisher() {
            super(null);
        }

        @Override
        public void publishLineStatuses(Map<String, Object> statusesByLineId) {
            lineBatches.add(statusesByLineId);
        }
    }

    private RecordingPublisher liveStreamPublisher;
    private LineService lineService;

    @BeforeEach
    void setUp() {
        liveStreamPublisher = new RecordingPublisher();
        lineService = new LineService(tflApiClient, fcmService, liveStreamPublisher);
        // A single mode: the stubs below match any mode, so a second one would
        // process the same line twice and double every assertion count.
        ReflectionTestUtils.setField(lineService, "tflTransportModes", "tube");
    }

    /** A raw TfL line payload, in the shape {@code getLineStatuses} returns. */
    private static Map<String, Object> rawLine(String id, String severity, String reason) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("statusSeverityDescription", severity);
        detail.put("reason", reason);
        Map<String, Object> line = new HashMap<>();
        line.put("id", id);
        line.put("name", id);
        line.put("lineStatuses", List.of(detail));
        return line;
    }

    /**
     * Run one sync to establish the baseline, then forget everything it did so a
     * test can assert purely on what the SECOND sync produces.
     */
    private void prime(Map<String, Object> initial) {
        when(tflApiClient.getLineStatuses(anyString())).thenReturn(List.of(initial));
        lineService.syncLineStatuses();
        clearInvocations(fcmService);
        liveStreamPublisher.lineBatches.clear();
    }

    @Test
    void testFirstSync_PrimesBaselineWithoutNotifyingUsers() {
        // Nothing is persisted, so a cold start sees EVERY line as new. Pushing
        // FCM for those would notify every subscribed user, on every restart and
        // every deploy, about changes that never happened.
        when(tflApiClient.getLineStatuses(anyString()))
                .thenReturn(List.of(rawLine("victoria", "Severe Delays", "Signal failure")));

        List<LineStatusResponse> result = lineService.syncLineStatuses();

        assertEquals(1, result.size());
        assertEquals("victoria", result.get(0).getId());
        verify(fcmService, never()).publishAll(anyMap());

        // The stream push is NOT suppressed: the backend's cache is empty at
        // this point and needs current state, and an update only refreshes what
        // a client already displays.
        assertFalse(liveStreamPublisher.lineBatches.isEmpty(),
                "the backend must still be seeded on the priming run");
    }

    @Test
    void testFirstSync_TflUnreachable_StaysUnprimed() {
        // Regression guard: priming used to complete unconditionally. If TfL
        // returned nothing on the first run the cache stayed cold but the flag
        // flipped, so the NEXT healthy sync saw every line as new and fired the
        // exact FCM storm the flag exists to prevent.
        when(tflApiClient.getLineStatuses(anyString())).thenReturn(Collections.emptyList());
        lineService.syncLineStatuses();

        when(tflApiClient.getLineStatuses(anyString()))
                .thenReturn(List.of(rawLine("victoria", "Severe Delays", "Signal failure")));
        lineService.syncLineStatuses();

        verify(fcmService, never()).publishAll(anyMap());
    }

    @Test
    void testSyncLineStatuses_RealChange_PushesNotification() {
        prime(rawLine("victoria", "Good Service", ""));

        when(tflApiClient.getLineStatuses(anyString()))
                .thenReturn(List.of(rawLine("victoria", "Minor Delays", "Busy")));
        List<LineStatusResponse> result = lineService.syncLineStatuses();

        assertEquals("Minor Delays", result.get(0).getStatusSeverityDescription());
        assertEquals("tube", result.get(0).getMode());
        verify(fcmService, times(1)).publishAll(anyMap());
    }

    @Test
    void testSyncLineStatuses_NoChange_NoPush() {
        // TfL sends an empty reason for Good Service and we substitute a random
        // message, so a naive comparison would report a "change" on every poll.
        prime(rawLine("victoria", "Good Service", ""));

        when(tflApiClient.getLineStatuses(anyString()))
                .thenReturn(List.of(rawLine("victoria", "Good Service", "")));
        List<LineStatusResponse> result = lineService.syncLineStatuses();

        assertTrue(TflUtils.GOOD_SERVICE_MESSAGES.contains(result.get(0).getReason()),
                "the generated Good Service reason must be preserved, not regenerated");
        verify(fcmService, never()).publishAll(anyMap());
        assertTrue(liveStreamPublisher.lineBatches.isEmpty(),
                "an unchanged status must not be dispatched to the stream either");
    }

    @Test
    void testSyncLineStatuses_Changed_PublishesToLiveStream() {
        // This dispatch is the backend's ONLY live source of line status now
        // that its Firestore listener is gone — a miss here leaves the stream
        // stuck until the backend's own 60s TfL fallback fires.
        prime(rawLine("victoria", "Good Service", ""));

        when(tflApiClient.getLineStatuses(anyString()))
                .thenReturn(List.of(rawLine("victoria", "Severe Delays", "Signal failure")));
        lineService.syncLineStatuses();

        assertFalse(liveStreamPublisher.lineBatches.isEmpty(),
                "a changed status must be dispatched to the live stream");
        assertTrue(liveStreamPublisher.lineBatches.get(0).containsKey("victoria"),
                "the stream map must be keyed by BARE lineId, not the FCM topic");
    }
}
