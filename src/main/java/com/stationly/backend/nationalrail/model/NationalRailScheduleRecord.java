package com.stationly.backend.nationalrail.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** One service plus its calling points — the unit the timetable service hands the repository. */
@Data
@Builder
public class NationalRailScheduleRecord {
    private NationalRailServiceRow service;
    private List<NationalRailCallingPointRow> callingPoints;
}
