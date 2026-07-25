package com.stationly.backend.nationalrail.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One parsed Darwin Push Port {@code <TS>} (Train Status) message.
 *
 * Darwin notifies by SERVICE, not by station: a single TS is about one train
 * run ({@code rid}) and carries updates for MANY calling points at once. Each
 * {@link LocationUpdate} is a station on that train's route whose board just
 * changed. Locations are keyed by TIPLOC (Darwin's location code) — NOT CRS and
 * NOT naptan — so the listener must map TIPLOC→CRS→naptanId.
 *
 * {@code messageTimestamp} is the {@code <Pport ts>} value: the ordering key for
 * discarding duplicate / out-of-order frames.
 */
@Data
@Builder
public class DarwinTrainStatusFrame {
    private String rid;
    private LocalDate serviceStartDate;   // <TS ssd>
    private OffsetDateTime messageTimestamp; // <Pport ts>
    private List<LocationUpdate> locations;

    /** One updated calling point within a TS message. */
    @Data
    @Builder
    public static class LocationUpdate {
        private String tiploc;             // <Location tpl>
        private String arrivalEstimated;   // <arr et>
        private String arrivalActual;      // <arr at>
        private String departureEstimated; // <dep et>
        private String departureActual;    // <dep at>
        private String platform;           // <plat>
        private boolean cancelled;

        /** A no-op location (timetable-only, nothing live) is not worth a rebuild. */
        public boolean carriesLiveChange() {
            return cancelled
                    || isPresent(arrivalEstimated) || isPresent(arrivalActual)
                    || isPresent(departureEstimated) || isPresent(departureActual)
                    || isPresent(platform);
        }

        private static boolean isPresent(String s) {
            return s != null && !s.isBlank();
        }
    }
}
