package com.stationly.backend.nationalrail.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * All Darwin / Rail Data Marketplace credentials and endpoints, namespaced
 * under {@code darwin.*}. Everything else in the module speaks in
 * NationalRail/domain terms; only the wire boundary uses the vendor prefix.
 */
@Configuration
@ConfigurationProperties(prefix = "darwin")
@Data
public class NationalRailProperties {

    /** OpenLDBWS token (Rail Data Marketplace) — the per-station board source on each drift. */
    private String ldbwsToken;
    private String ldbwsEndpoint = "https://lite.realtime.nationalrail.co.uk/OpenLDBWS/ldb11.asmx";

    /** Darwin "Real Time Train Information" — RDM delivers the Push Port as a
     *  Kafka feed of schemaless JSON (Darwin v17), NOT legacy STOMP/XML. These
     *  five values come from the product's Pub/Sub tab on raildata.org.uk. */
    private String kafkaBootstrapServers;
    private String kafkaConsumerKey;      // SASL username
    private String kafkaConsumerSecret;   // SASL password
    private String kafkaGroupId;
    private String kafkaTopic;

    /** Darwin Reference data — the TIPLOC↔CRS location table (needed to map Push Port locations). */
    private String referenceDataUrl;
    private String referenceDataUsername;
    private String referenceDataPassword;

    /** Darwin Timetable feed — the daily rid-keyed schedule snapshot (the SQL baseline). */
    private String timetableFeedUrl;
    private String timetableFeedUsername;
    private String timetableFeedPassword;
}
