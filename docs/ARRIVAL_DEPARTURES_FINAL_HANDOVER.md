# ArrivalDepartures (Departure Board) Parity — Final Handover (2026-07-19)

**Status: COMPLETE, staging-verified, zero regressions. NOT committed / NOT pushed /
prod untouched.** Working tree on `dev_13Jul`, deployed to staging via
`local_scripts/staging_deploy.sh` (systemd `stationly.service` on 79.72.94.209).
Backend companion doc: `stationly-backend/docs/ARRIVAL_DEPARTURES_FINAL_HANDOVER.md`.

## 1. What & why

The Syncer's 30s FCM loop built every payload from bulk `/Mode/{mode}/Arrivals` — so
XR/OG termini pushed "Check Front of Train" + junk "due now" rows, and cancelled
trains were pushed as running, while the backend REST path (already upgraded) showed
real departures. User-confirmed strategy: **alongside the bulk mode calls, every
SUBSCRIBED station where elizabeth-line or overground passes (solo/terminus/partial/
mixed) gets parallel per-line `/StopPoint/{id}/ArrivalDepartures` calls, merged with
the bulk data; push and every other logic unchanged.**

## 2. Architecture (mirrors stationly-backend 1:1 by name and rule)

- `service/predictionsources/`:
  - `PredictionSource` (interface) + `StationPredictionContext` (per station × mode
    cycle; carries that station's bulk arrivals + board entries + helpers).
  - `TubeDlrBusTramMixPredictionSource` — VERBATIM extraction of the old
    per-station transform body (CFT relabelling, direction grouping, filters,
    `limit(10)`); universal fallback.
  - `ElizabethOvergroundPredictionSource` — board rows with the backend's exact
    filters (Cancelled/NotStopping skip, eta=estimated??scheduled, dest==self skip,
    Delayed exempt from 2-min cutoff, duplicate collapse, board rows exempt from the
    far-future-unassigned rule) and the CORRECTED direction chain (destination map
    first, conflict-dropped maps, uniformity, terminus, outbound). Per-line
    countdown fallback: a dead board never blanks a line.
  - `PredictionSourceFactory` — board entries present → board source, else countdown.
  - `ArrivalDeparturesData` — resolved per-cycle board responses.
- `service/ArrivalDeparturesFetchService` — plans per cycle: candidates =
  subscribed ∩ stations-with-this-mode (1h-cached via new
  `LocalDatabaseService.getStationsByMode`, json_each over `modes_json`);
  **route termini always planned first** (via `RouteDirectionResolver.
  resolveDepartingDirection != null`), remaining stations rotate; **all-or-nothing
  per station** under `tfl.arrival-departures.max-calls-per-cycle`. Futures start
  BEFORE the bulk fetch blocks (overlapped latency); joined in `await()`.
- `client/TflApiClient.getArrivalDepartures` — **rate-limited** (`TflRateLimiter`
  210ms global gate; note the bulk arrivals calls are deliberately unthrottled as
  before), returns `[]` on any error → per-line fallback.
- `DataTransformationService` — per-station body now delegates to the factory;
  grouping keys, `Station_<naptanId>` topics, per-mode cycles, ChangeDetection,
  heartbeat, wipes, `pruneToFitFCM` (4KB) all UNTOUCHED. Board-planned stations are
  processed even with zero arrivals (quiet-hour terminus timetable). Shared helpers
  exposed: `isUnassignedPlatform`, `cleanDestinationName`, `DEPARTED_CUTOFF` (2min,
  lockstep with backend `predictionUtils.ts`).

## 3. Config

```
tfl.arrival-departures.enabled=${TFL_ARRIVAL_DEPARTURES_ENABLED:true}      # kill switch → exact pre-board behavior
tfl.arrival-departures.max-calls-per-cycle=${TFL_ARRIVAL_DEPARTURES_MAX_CALLS_PER_CYCLE:60}
```
Cap is PER MODE CYCLE (worst theoretical 60+60=120 calls ≈ 25s limiter pacing —
realistic max ≈ 86 since elizabeth needs ≤~26; tune to 45 if both modes ever
saturate). Past the cap: station serves countdown (old behavior) this cycle and
moves up the rotation next cycle; termini never rotate out. NAP MODE unchanged.

## 4. Budget & observed load (staging, live)

- Plans: `[overground] 17 stations / 20 calls (5 termini)` + `[elizabeth-line]
  9 stations / 9 calls (4 termini)` ≈ 29 calls/cycle ≈ 58 req/min vs 500 shared key.
- Full 5-mode cycles 6-17s vs 30s budget; zero 429s/errors across all observations.
- FCM volume DROPS at board stations (stable scheduled times → fewer change pushes;
  5-cycle heartbeat keeps clients fresh). Pushes remain subscribed-topics-only.

## 5. Validation evidence (2026-07-19)

- `PredictionSourceParityTest` — 13/13: countdown-only output **byte-identical** to
  a verbatim LegacyTransform oracle (modulo `lut`); cancelled/NotStopping never
  returned; Delayed kept at updated time; CFT→real departures; empty board = exact
  legacy payload; far-future unplatformed BOARD rows kept; duplicate collapse;
  Romford mixed-platform labels can't flip buckets. (No Mockito — inline mocks
  unsupported on this JDK; `NullRouteDirectionResolver` subclass instead.)
- Push-vs-REST differ (staging 📦 payloads vs staging REST, same window):
  ABW 10/10 push rows matched, FRNDXR 20/20, HGHI 29/30 (1 = train departed between
  snapshots), LIVST 10/10 — **0 unexplained rows**; REST extras = beyond the push's
  `limit(10)`/4KB caps (pre-existing design).
- Before/after (Abbey Wood): old Syncer pushed 16 junk "due now" + 16 CFT rows; new
  payload = real HT4/Reading/Maidenhead departures at true times.
- Payload log `SYNC: 📦` is now DEBUG (`logging.level.com.stationly=DEBUG` to view).

## 6. Logs (journalctl -u stationly.service)

- `SYNC: 🚉 [{mode}] ArrivalDepartures plan: N stations / M calls (K termini…)`
- `SYNC: 🔀 {naptanId} → arrival-departures` per routed station;
  `(N via arrival-departures)` in the transform summary.
- `SYNC: ⚠️ … No usable board rows for {lines} at {station}` → per-line fallback
  engaged (normal late-night; suspicious all-day).

## 7. Known gaps / follow-ups

- **Multi-mode naptanIds get alternating per-mode payloads on one topic**
  (PRE-EXISTING; deferred by user):
  https://github.com/MaverickNyk/StationlySyncer/issues/56 — exactly 910GLIVST /
  910GSTFD / 910GROMFORD (+ intermittent Kew Gardens oddity). First step: verify
  StationlyUI merges lines by lineId.
- Route-based `resolveDirectionTowards` pass not mirrored (v1: dest-map-first makes
  it near-redundant; revisit if quiet-hour through-station bucketing ever matters).
- One late-night (~23:30+) cycle observation recommended before merge.

## 8. Ops

- Staging deploy: `local_scripts/staging_deploy.sh`. Prod: CI on push to
  `release_prod` — DO NOT push until sign-off.
- Rollback: `TFL_ARRIVAL_DEPARTURES_ENABLED=false` + restart (no redeploy), or
  redeploy `main`. Payload schema unchanged (`id,name,lut,lines→dirs→preds`).
