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
    public DataRepository<Station, String> stationRepository(Firestore firestore) {
        return new GenericFirestoreRepository<>(
                firestore,
                "stations",
                Station.class,
                Station::getNaptanId);
    }



    @Bean
    public DataRepository<LineStatusResponse, String> lineStatusRepository(Firestore firestore) {
        return new GenericFirestoreRepository<>(
                firestore,
                "lineStatuses",
                LineStatusResponse.class,
                LineStatusResponse::getId);
    }
}
