package com.stationly.backend.config;

import com.google.cloud.firestore.Firestore;
import com.stationly.backend.model.*;
import com.stationly.backend.repository.DataRepository;
import com.stationly.backend.repository.firestore.GenericFirestoreRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for repository beans.
 * Creates generic Firestore repositories for each entity type.
 */
@Configuration
public class RepositoryConfig {



    @Bean
    public DataRepository<Station, String> stationRepository(
            @org.springframework.beans.factory.annotation.Autowired(required = false) Firestore firestore) {
        return new GenericFirestoreRepository<>(
                firestore,
                "stations",
                Station.class,
                Station::getNaptanId);
    }

    // lineStatusRepository bean removed: line statuses are no longer persisted
    // to Firestore. LineService keeps them in memory and pushes changes straight
    // to the backend's /internal/line-status-updates endpoint.
}
