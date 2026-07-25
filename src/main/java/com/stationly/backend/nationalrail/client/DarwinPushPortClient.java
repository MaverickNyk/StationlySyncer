package com.stationly.backend.nationalrail.client;

import com.stationly.backend.nationalrail.config.NationalRailProperties;
import com.stationly.backend.nationalrail.dto.DarwinScheduleChangeFrame;
import com.stationly.backend.nationalrail.dto.DarwinTrainStatusFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Consumes the Darwin "Real Time Train Information" feed. On the Rail Data
 * Marketplace this is a **Kafka** stream of **schemaless JSON** (Darwin v17) —
 * NOT the legacy STOMP/ActiveMQ + gzipped-XML Push Port. Deliberately dumb: it
 * parses each message and routes it to the registered consumer; it knows
 * nothing about mapping, coverage, SQL or FCM (the listener owns that).
 *
 * Two message classes drive the mirror: train-status (timing/platform/cancel,
 * → {@link DarwinTrainStatusFrame}) and schedule (cancel/re-plan,
 * → {@link DarwinScheduleChangeFrame}).
 *
 * TODO: wire a Kafka consumer (spring-kafka / kafka-clients with SASL) once the
 * Pub/Sub credentials are in: bootstrap servers + consumer key/secret (SASL) +
 * group id + topic (all from the product's Pub/Sub tab). Per record: parse the
 * Darwin v17 JSON, dispatch each status/schedule message to the matching
 * consumer; invoke {@code onDisconnect} on a fatal consumer error (Kafka
 * auto-reconnects, but a rebalance/gap should re-anchor covered stations). Ref:
 * openraildata/kafka-client-rdm-darwin.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DarwinPushPortClient {

    private final NationalRailProperties properties;

    private volatile boolean running = false;

    public void connect(Consumer<DarwinTrainStatusFrame> onTrainStatus,
                        Consumer<DarwinScheduleChangeFrame> onScheduleChange,
                        Runnable onDisconnect) {
        log.info("🚉 [Darwin] Connecting Kafka consumer bootstrap={} topic={} group={}",
                properties.getKafkaBootstrapServers(), properties.getKafkaTopic(), properties.getKafkaGroupId());
        // TODO: build the SASL Kafka consumer, subscribe to the topic, poll loop,
        // parse Darwin v17 JSON → status/schedule frames, dispatch.
        running = true;
    }

    public void disconnect() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }
}
