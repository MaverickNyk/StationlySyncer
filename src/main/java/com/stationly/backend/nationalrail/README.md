# National Rail (Darwin) module

Self-contained package (`com.stationly.backend.nationalrail`) adding National
Rail departures alongside the TfL feed. Gated by `nationalrail.enabled` (default
**off**). Own SQLite mirror, no shared DB — dark-launchable independently.

## How it works — a local Darwin mirror

The app is a **pure renderer**: it subscribes to `Station_<naptanId>` and draws
whatever `FcmPayload` board arrives there — it can't tell TfL from National Rail
and cannot change. So this module produces the **same board payload** the TfL
path does (`StationPredictions`, Jackson-serialised → `FcmPayload`) and pushes it
through the **same `FcmService`**.

Boards are computed from a **SQL mirror of Darwin**, not per-request API calls:

```
03:00 daily   Timetable file ──► SQL baseline (every service, every station, today)
all day       Push Port TS/SC deltas ──► mutate the matching SQL rows (rid-keyed)
read/push     board = SQL query "next N departures at CRS from now"  (NO external call)
```

- **Baseline (`NationalRailTimetableService`):** ingest Darwin's daily rid-keyed
  timetable file into `nr_service` + `nr_calling_point` (times → epoch ms, day
  rollover handled, TIPLOC→CRS resolved, non-station timing points dropped).
- **Deltas (`NationalRailDeltaApplier`):** Push Port `<TS>` (timing/platform/cancel)
  and `<schedule>` (whole-service cancel / re-plan) mutate rows by `rid`+`tiploc`.
  Applied **covered-only** so the national firehose's write load stays proportional
  to what we serve. Reports impacted covered stations.
- **Board (`NationalRailBoardEngine`):** a pure SQL query → `StationPredictions`.
  Because it's local, the heartbeat can run it for every covered station for free.

Two triggers push a rebuilt board (via `NationalRailStationRefreshService` →
content-diff → `FcmService`):
- **Push Port drift** → debounced rebuild of just the impacted covered stations (real-time).
- **Heartbeat** (default 15 min, pure SQL) → every covered station, so quiet
  boards age out departed trains and pull in newly-horizon services.

## Why a mirror (not per-request LDBWS)

Near-zero external calls on the read/heartbeat path, and it scales to any number
of stations. National Rail is the **most drift-heavy mode** (platform
confirmations alone fire a change per service, plus delays/cancellations) — the
mirror + delta merge is what surfaces that live. OpenLDBWS is kept only as a
**v2 reconciliation / re-anchor** tool, not on the hot path.

## Identifier chain

`TIPLOC (Push Port) → CRS (timetable/board) → naptanId (the app)`, in
`NationalRailStationMappingService`. TIPLOC→CRS + station names from Darwin
**reference data**; CRS→naptan is derivation-first (`9100`+CRS) with an override
index for TfL `910G…` hubs from `Station.crs`.

## Layers

| Package     | Role |
|-------------|------|
| `client`    | Darwin wire — Timetable file, Real Time (Kafka), Reference data, LDBWS (v2). **Stubs today.** |
| `dto`       | Vendor frames in (`DarwinScheduleService`, `DarwinTrainStatusFrame`, `DarwinScheduleChangeFrame`, `DarwinLocationRef`); board-query row out. |
| `model`     | SQL row types (`NationalRailServiceRow`, `NationalRailCallingPointRow`, record). |
| `repository`| The SQLite mirror: baseline replace, delta apply, board query, retention. |
| `policy`    | `NationalRailBoardKeys` — the backend-coupled lineId/direction keys. |
| `service`   | timetable ingest, delta applier, board engine, mapping, coverage, change detector, debouncer, refresh, push, listener. |
| `scheduler` | 03:00 timetable load, 02:45 reference refresh, heartbeat sweep. |
| `util`      | FCM topic, UK time + HH:mm↔epoch↔ISO. |

## ⚠️ Backend-coupled keys (the empty-board trap)

The app matches `payload.lines[selection.line]` and `dirs[selection.direction]`;
a key the user's stored selection doesn't match renders an **empty board**.
`NationalRailBoardKeys` MUST reproduce exactly the lineId + direction the backend
assigns an NR station. Defaults are placeholders (single `national-rail` line +
`outbound` direction) — finalise against backend NR modeling before go-live.

## Stubs to implement (need credentials + protocol code)

| Stub | Protocol |
|------|----------|
| `DarwinTimetableFeedClient` | fetch + gunzip + parse the daily rid-keyed timetable → `DarwinScheduleService`. |
| `DarwinPushPortClient` | **Kafka** consumer (spring-kafka, SASL) → parse Darwin v17 **JSON** → `<TS>`/`<schedule>` frames. (RDM = Kafka/JSON, not STOMP/XML.) |
| `DarwinReferenceDataClient` | fetch/parse TIPLOC↔CRS + station names. |
| `DarwinLdbwsClient` | (v2) SOAP re-anchor/reconciliation — not on the v1 hot path. |

## Pre-work in other repos

- **stationly-backend:** `DarwinPredictionSource` (cold-open board) — could read
  this same mirror via an internal endpoint, or call LDBWS itself. Placeholder in
  `PredictionSourceFactory`.
- **TfL station sync:** populate `Station.crs` + add `national-rail` mode (else
  coverage + CRS overrides are empty).
- Finalise `NationalRailBoardKeys` against the backend line/direction model.

See `docs/NATIONAL_RAIL_DARWIN_HANDOVER.md` for the full handover + Darwin
registration.
