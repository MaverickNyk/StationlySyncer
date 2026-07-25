package com.stationly.backend.nationalrail.dto;

/**
 * One entry of Darwin's location reference data: the mapping between a TIPLOC
 * (used on the Push Port) and a CRS (used by OpenLDBWS and derivable to naptan).
 * {@code crs} is null for locations that aren't public stations (junctions,
 * timing points) — those are simply skipped when the index is built.
 */
public record DarwinLocationRef(String tiploc, String crs, String name) {}
