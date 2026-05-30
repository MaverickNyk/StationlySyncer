package com.stationly.backend.service;

import com.stationly.backend.client.TflApiClient;
import com.stationly.backend.client.TflRateLimiter;
import com.stationly.backend.model.Station;
import com.stationly.backend.repository.DataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StationServiceTest {

    // Manual Stub because Mockito fails with Java 25/ByteBuddy
    static class StubTflApiClient extends TflApiClient {
        public StubTflApiClient() {
            super(WebClient.builder(), new TflRateLimiter());
        }

        @Override
        public List<Map<String, Object>> getLinesByMode(String mode) {
            Map<String, Object> line39 = new HashMap<>();
            line39.put("id", "39");
            line39.put("name", "39");
            return Collections.singletonList(line39);
        }

        @Override
        public List<Map<String, Object>> getStopPointsByLine(String lineId) {
            Map<String, Object> stop1 = new HashMap<>();
            stop1.put("naptanId", "490000184Z");
            stop1.put("commonName", "Putney Bridge Station");
            stop1.put("lat", 51.46792);
            stop1.put("lon", -0.20931);
            stop1.put("stopType", "NaptanPublicBusCoachTram");
            stop1.put("indicator", "Stop FC");
            stop1.put("stopLetter", "FC");
            return Collections.singletonList(stop1);
        }

        @Override
        public Map<String, Object> getRouteSequence(String lineId, String direction) {
            return null;
        }
    }

    private StubTflApiClient tflApiClient;

    @Mock
    private DataRepository<Station, String> stationRepository;

    @Mock
    private LocalDatabaseService localDatabaseService;

    private StationService stationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        tflApiClient = new StubTflApiClient();
        stationService = new StationService(tflApiClient, stationRepository, localDatabaseService, null);
    }

    @Test
    void testSyncStationsByMode_AggregatesAndSavesStations() {
        // Mock Existing Data in SQLite
        when(localDatabaseService.getStationsBySearchKey(anyString())).thenReturn(Collections.emptyList());
        when(localDatabaseService.getStationsExceptStopType(anyString())).thenReturn(Collections.emptyList());

        // Execute
        stationService.syncStationsByMode("bus");

        // Verify Save (Simulate waiting because of async execution in service)
        // Actually the service waits for futures, so it is synchronous for the caller.

        ArgumentCaptor<List<Station>> captor = ArgumentCaptor.forClass(List.class);
        verify(stationRepository, atLeastOnce()).saveAll(captor.capture());

        List<Station> savedStations = new ArrayList<>();
        for (List<Station> batch : captor.getAllValues()) {
            savedStations.addAll(batch);
        }

        assertEquals(1, savedStations.size());
        Station s = savedStations.get(0);
        assertEquals("490000184Z", s.getNaptanId());
        assertEquals("Putney Bridge Station", s.getCommonName());
        assertEquals("Stop FC", s.getIndicator());
        assertEquals("FC", s.getStopLetter());
        assertTrue(s.getModes().containsKey("bus"));
    }
}
