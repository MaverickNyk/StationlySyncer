package com.stationly.backend.service;

import com.stationly.backend.client.TflApi;
import com.stationly.backend.model.LineStatusResponse;
import com.stationly.backend.repository.DataRepository;
import com.stationly.backend.util.TflUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LineServiceTest {

    @Mock
    private TflApi tflApiClient;
    @Mock
    private DataRepository<LineStatusResponse, String> lineStatusRepository;
    @Mock
    private NotificationService fcmService;

    private LineService lineService;

    @BeforeEach
    void setUp() {
        lineService = new LineService(tflApiClient, lineStatusRepository, fcmService);
        ReflectionTestUtils.setField(lineService, "tflTransportModes", "tube,bus");
    }

    @Test
    void testSyncLineStatuses_NewStatus_PushesNotification() {
        // Given
        when(lineStatusRepository.findAll()).thenReturn(Collections.emptyList());

        Map<String, Object> rawStatus = new HashMap<>();
        rawStatus.put("id", "victoria");
        rawStatus.put("name", "Victoria");
        List<Map<String, Object>> statuses = new ArrayList<>();
        Map<String, Object> statusDetail = new HashMap<>();
        statusDetail.put("statusSeverityDescription", "Severe Delays");
        statusDetail.put("reason", "Signal failure");
        statuses.add(statusDetail);
        rawStatus.put("lineStatuses", statuses);

        when(tflApiClient.getLineStatuses("tube")).thenReturn(List.of(rawStatus));

        // When
        List<LineStatusResponse> result = lineService.syncLineStatuses();

        // Then
        assertEquals(1, result.size());
        assertEquals("victoria", result.get(0).getId());
        assertEquals("Severe Delays", result.get(0).getStatusSeverityDescription());
        assertEquals("tube", result.get(0).getMode());

        verify(fcmService, times(1)).publishAll(anyMap());
        verify(lineStatusRepository, times(1)).saveAll(anyList());
    }

    @Test
    void testSyncLineStatuses_NoChange_NoPush() {
        // Given
        LineStatusResponse existing = LineStatusResponse.builder()
                .id("victoria")
                .mode("tube")
                .statusSeverityDescription("Good Service")
                .reason("Random Reason 1")
                .build();
        when(lineStatusRepository.findAll()).thenReturn(List.of(existing));

        // Mock TfL response same as existing (but note: TfL gives empty reason for Good
        // Service, we generate random)
        // Here we test logic: if we generated same status, should not push.
        // But mapToLineStatusResponse will generate a NEW random reason if input is
        // empty.

        Map<String, Object> rawStatus = new HashMap<>();
        rawStatus.put("id", "victoria");
        rawStatus.put("name", "Victoria");
        List<Map<String, Object>> statuses = new ArrayList<>();
        Map<String, Object> statusDetail = new HashMap<>();
        statusDetail.put("statusSeverityDescription", "Good Service");
        statusDetail.put("reason", ""); // Empty from TfL
        statuses.add(statusDetail);
        rawStatus.put("lineStatuses", statuses);

        when(tflApiClient.getLineStatuses("tube")).thenReturn(List.of(rawStatus));

        // Assuming TflUtils.GOOD_SERVICE_MESSAGES contains "Random Reason 1" for the
        // optimization test
        // Reflection to inject specific message logic is hard, but we can rely on
        // existing constants?
        // Let's assume input existing reason IS in the list (we can't easily check
        // internal list but we know it's there if we pick one)
        // Actually, let's pick a known message from TflUtils if possible.
        // Or just trust the logic: if old reason is "Safe" (in list) and new is "Good
        // Service", we keep old.

        // Let's manually set existing reason to one we know is in list:
        String knownMessage = TflUtils.GOOD_SERVICE_MESSAGES.get(0);
        existing.setReason(knownMessage);

        // When
        List<LineStatusResponse> result = lineService.syncLineStatuses();

        // Then
        LineStatusResponse updated = result.get(0);
        assertEquals(knownMessage, updated.getReason()); // Should be preserved
        verify(fcmService, never()).publishAll(anyMap());
    }

    @Test
    void testSyncLineStatuses_RealChange_PushesNotification() {
        // Given
        LineStatusResponse existing = LineStatusResponse.builder()
                .id("victoria")
                .mode("tube")
                .statusSeverityDescription("Good Service")
                .reason(TflUtils.GOOD_SERVICE_MESSAGES.get(0))
                .build();
        when(lineStatusRepository.findAll()).thenReturn(List.of(existing));

        // New status: Minor Delays
        Map<String, Object> rawStatus = new HashMap<>();
        rawStatus.put("id", "victoria");
        rawStatus.put("name", "Victoria");
        List<Map<String, Object>> statuses = new ArrayList<>();
        Map<String, Object> statusDetail = new HashMap<>();
        statusDetail.put("statusSeverityDescription", "Minor Delays");
        statusDetail.put("reason", "Busy");
        statuses.add(statusDetail);
        rawStatus.put("lineStatuses", statuses);

        when(tflApiClient.getLineStatuses("tube")).thenReturn(List.of(rawStatus));

        // When
        lineService.syncLineStatuses();

        // Then
        verify(fcmService, times(1)).publishAll(anyMap());
    }


}
