package com.stationly.backend.nationalrail.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * One service parsed from the daily Darwin timetable file — the `rid`-keyed
 * baseline every live delta is later applied on top of. Destination is the
 * final calling point; carried here so the board engine can show "to X"
 * without re-deriving it per query.
 */
@Data
@Builder
public class DarwinScheduleService {
    private String rid;
    private LocalDate serviceStartDate; // ssd
    private String uid;
    private String toc;
    private String destinationTiploc;
    private List<DarwinScheduleCall> calls;
}
