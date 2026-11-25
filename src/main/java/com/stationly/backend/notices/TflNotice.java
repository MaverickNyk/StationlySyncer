package com.stationly.backend.notices;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One TfL stop notice, as `/StopPoint/Mode/{modes}/Disruption` gives it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TflNotice(String atcoCode, String commonName, String mode, String type, String appearance,
                        String fromDate, String toDate, String description) {
}
