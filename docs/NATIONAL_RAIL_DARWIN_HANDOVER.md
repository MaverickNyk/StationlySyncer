# National Rail (Darwin) — Syncer Integration Handover

**Date:** 2026-07-21, **updated 2026-07-25** (see §0)
**Branch:** `dev_national_rail_integration`
**Status:** 🟡 Module compiles (`mvn compile` → BUILD SUCCESS), **disabled by default**, four Darwin wire clients stubbed. Not deployed.
**Scope:** syncer side. Backend station model is now **built** — see §0.

---

## 0. UPDATE 2026-07-25 — backend work changed several assumptions here

The backend side was built out on 2026-07-25. **Read
`stationly-backend/docs/NATIONAL_RAIL_HANDOVER.md` first** — it is the fuller
document and it supersedes parts of this one. Both repos are on branch
`dev_national_rail_integration`.

State: 9 test station docs + `lines/national-rail` written to **staging**
(`mindthetimefcm`). Nothing in production. One product decision is open (§0.5).

### 0.1 ❌ Pre-work item 4 is wrong — the TfL sync is not involved

§6 says *"TfL station sync: populate `Station.crs` from StopPoint
`additionalProperties` + add `national-rail` to affected stations' modes."*
**That is not the design.** National Rail stations are their **own** Firestore
docs, keyed `9100<TIPLOC>`, seeded from NaPTAN by
`stationly-backend/src/scripts/seedNationalRailStations.ts`.

Why: the TfL sync owns the `910G…` namespace and its replication `apply` is a
**full doc replace**, so anything we wrote into a TfL-owned doc would be silently
clobbered on the next sync. Writing our own namespace makes that impossible.

Verified: **0 id collisions** across all 2,645 generated ids vs all 20,208
existing station docs. And **164** NR stations share a `commonName` with an
existing TfL doc, so they group automatically at query time (`getGroupKey` falls
back to `commonName` — `icsCode`/`stationNaptan` are null on *every* doc) and
their modes get unioned. No merge step, ever.

`Station.crs` (the field added in this branch) is still correct and still needed —
it just comes from NaPTAN, not from TfL StopPoint properties.

### 0.2 `naptanId` = `"9100"` + **TIPLOC** — the CRS hop is redundant

Verified against the live NaPTAN area-910 CSV:
`Bicester Village CRS=BIT → 9100BCSTRTN` · `Cambridge CBG → 9100CAMBDGE` ·
`King's Cross KGX → 9100KNGX` · `Ely ELY → 9100ELYY`.

Push Port sends TIPLOC directly, so **naptanId is a pure string concat with no
lookup**. The TIPLOC→CRS→naptanId chain in `NationalRailStationMappingService` can
be collapsed. CRS is still needed for board grouping and LDBWS.

### 0.3 ⚠️ `NationalRailBoardKeys` defaults are wrong today

Current default is a single `national-rail` line + direction **`outbound`**. The
backend writes **compass words** (`northbound`, `southbound`, `eastbound`,
`westbound`) — so as it stands every NR board would render **empty**
(`payload.lines[selection.line].dirs[selection.direction]` misses).

The line key `"national-rail"` is correct and stable. The **direction key is the
open decision** (§0.5). Do not finalise `NationalRailBoardKeys` until it is
settled, and when it is, **read the direction from the station doc rather than
recomputing it** — deriving the same taxonomy independently in TypeScript and
Java is precisely how the two drift and boards go blank.

Real values now on staging: `9100KNGX ["northbound"]` · `9100PADTON
["westbound"]` · `9100STPX ["northbound","westbound"]` · `9100CLPHMJC` 3 ·
`9100MNCRPIC` 4 · `9100ELYY` 4.

### 0.4 The timetable file — no credentials needed, and its traps

The **Data files** tab of *Darwin Timetable Files* is a plain browser download.
The station/route seeding needs nothing else; `DARWIN_TIMETABLE_FEED_*` is only
for automating the daily pull.

Measured on `PPTimetable_20260725020500_v8.xml.gz` (9.8 MB):

| Element | Count |
|---|---|
| `Journey` | 52,528 (**37,012** after filters) |
| `IP` / `PP` | 377,373 calls / 335,975 passes |
| `OR` / `DT` | 41,853 / 41,858 |
| `OPOR/OPIP/OPDT` | ~10.7k each — operational, always exclude |
| `Association` | 8,399 |

Filters that must be applied: `isPassengerSvc="false"` (empty stock),
`trainCat="BS"` (rail-replacement **bus** — they appear with `*BUS` tiplocs),
`toc="XR"` (Elizabeth line, already a separate app mode).

> **Trap:** the reference file has 3,698 entries with a `crs`, but **1,054 are not
> stations** — bus stops (`… (Bus)`), platform splits (`Aberdare Platform 2`) and
> junctions whose `locname` is just the TIPLOC (`ABTSWDJ`, `ACTONTC`). Never use
> "has a CRS" as the station test.

> **Trap:** the file spans four `ssd` values unevenly — for King's Cross,
> `07-24: 1 · 07-25: 201 · 07-26: 167 · 07-27: 4`. It is a rolling snapshot of
> today plus part of tomorrow, **not** four days. `NationalRailTimetableService`
> must filter on `ssd`, which it already intends to — verify it does.

### 0.5 The open decision — affects the syncer's board keys

Two models for what a user selects, and therefore what key the board nests under:

- **A — compass directions** (what's on staging). Measured spread: 1 direction for
  151 stations, 2 for 2,041, 3 for 311, 4 for 88. Known defects: under-splits
  Paddington (Heathrow Express is 29% of departures and mixes with the West
  Country), over-splits Clapham Junction (two directions both labelled "Towards
  London Victoria").
- **B — destination CRS** (recommended). `direction` becomes a destination code or
  `ALL`, filtering on **calling points** — which is exactly `filterCrs`, the only
  filter LDBWS offers. Removes both defects with no thresholds.

For the syncer this is the difference between bucketing departures by compass
quadrant and bucketing by "does this service call at X". **B is simpler here** —
it is a direct `calling_point` lookup against the mirror.

### 0.6 Board-payload gaps this session confirmed

- **`board.max-departures=30` vs the FCM 4096-byte limit.** The NR push path does
  not run the TfL path's `pruneToFitFCM`. Either share that pruner or drop the
  default to ~6. Unfixed.
- **No status field.** `PredictionItem` is `{destId, platform, eta, displayName}`
  and the app renders a **countdown**. National Rail needs a scheduled clock time
  and *On time / Expected HH:MM / Cancelled*. Today the board query just excludes
  cancelled services, which on the most disruption-prone mode we carry is
  misleading rather than merely incomplete. **This needs an app release** and is
  independent of §0.5.
- **Platform:** present on **82.2%** of public calling points in the baseline —
  100% at Waterloo, Manchester Piccadilly, Edinburgh, Birmingham New Street, and
  **0% at Paddington**, where GWR assigns late. So platform genuinely arrives via
  the live TS feed, not the file. The mirror design already handles this; just
  don't treat a missing baseline platform as an error.
- **Self-terminating services:** Waterloo runs **72 a day** (Hounslow/Kingston
  loops departing and returning to Waterloo). They are its top "destination" at
  12%, and would render as `08:00 London Waterloo`. `NationalRailBoardEngine`
  needs the same rule as backend commit `a87895e` — label by a distinctive
  mid-point ("via Hounslow"), not the terminus. This is the §6 "circular services"
  limitation, and it is more common than "rare" suggests.

---

## 1. TL;DR

Adding **National Rail** departures (from **Darwin**, not TfL). The app
(`StationlyUI`) is a **pure renderer that cannot change**: it subscribes to
`Station_<naptanId>` and draws whatever `FcmPayload` board lands there (verified:
`FcmMessagingService.handlePredictionUpdate` reads `data["payload"]` as
`FcmPayload` → SQLite; no re-fetch). So the syncer emits the **same board payload
as the TfL path** through the **same `FcmService`**.

**Architecture — a local Darwin mirror (not per-request LDBWS):**

```
03:00 daily  Darwin Timetable file ─► SQL baseline (all services/stations today)
all day      Push Port TS + SC deltas ─► mutate matching SQL rows (by rid+tiploc)
read/push    board = SQL query "next N departures at CRS ≥ now"  ── no external call
             │ content-diff + heartbeat
             ▼
             FcmService.publishAll → Station_<naptanId>   (identical envelope to TfL)
```

Boards are computed from the mirror, so the read/heartbeat path makes **zero
external calls**. Push Port deltas keep the mirror live (National Rail is the
most drift-heavy mode — platform confirmations, delays, cancellations). LDBWS is
kept for **v2 reconciliation only**, off the hot path.

(Design history: we tried signal-push, then per-request-LDBWS-on-drift; both were
scrapped once the app-renders-payload constraint and the I/O cost were pinned
down. This mirror design is the endpoint.)

---

## 2. Darwin background (context)

- **Push Port notifies by SERVICE (`rid`), not station.** One `<TS>` carries
  updates for many calling points; each `<Location>` (keyed by **TIPLOC**) is a
  station whose board changed. `<schedule>` (SC) messages carry whole-service
  cancel / re-plan — **you must apply SC too**, or the mirror drifts wrong by
  mid-day (cancelled trains linger, new services never appear).
- **Three id spaces:** TIPLOC (Push Port) → CRS (timetable/board) → naptanId (app).
  `9100`+CRS derivable; TfL `910G…` hubs need the override index from `Station.crs`.
- **Baseline source = the daily Darwin _Timetable file_** (gzipped XML, `rid`-keyed
  `<Journey>` + calling points with public times + activity flags).
- Push Port **does not replay** missed messages; gzipped XML under `<Pport ts=…>`.
- Full national scale ≈ **250–300k calling-point rows ≈ ~100–150 MB SQLite** — trivial.
  Real cost of "full" is delta-processing CPU, which the **covered-only delta apply** avoids.

---

## 3. What was built (37 files, `…/nationalrail/`)

```
repository/ NationalRailScheduleRepository(+Impl)   SQLite mirror: baseline/delta/board/purge  ← core
service/    NationalRailTimetableService(+Impl)     daily timetable file → SQL baseline
            NationalRailDeltaApplier                Push Port TS/SC → mutate SQL, report impacted (covered-only)
            NationalRailBoardEngine                 SQL query → StationPredictions (no external call)
            NationalRailStationRefreshService       build board + content-diff + push (drift & heartbeat converge here)
            NationalRailStationMappingService(+Impl) TIPLOC→CRS→naptan + CRS→name, in-memory
            NationalRailCoverageService             subscribed ∩ national-rail
            NationalRailBoardChangeDetector         in-memory content-diff + heartbeat gate
            NationalRailRefreshDebouncer            coalesce drift bursts per station
            NationalRailPushNotifier(+Impl)         push board via NotificationService
            NationalRailPushPortListenerService     orchestrator (gated on enabled)
scheduler/  NationalRailTimetableLoadScheduler      03:00 + startup baseline load
            NationalRailReferenceDataScheduler      02:45 TIPLOC↔CRS refresh
            NationalRailHeartbeatScheduler          sweep covered (default 15m, pure SQL)
client/     DarwinTimetableFeedClient   🔴 STUB   daily timetable file (XML)
            DarwinPushPortClient        🔴 STUB   Kafka consumer, Darwin v17 JSON → TS/SC frames
            DarwinReferenceDataClient   🔴 STUB   TIPLOC↔CRS + names
            DarwinLdbwsClient           🔴 STUB   v2 reconciliation only
dto/        DarwinScheduleService, DarwinScheduleCall, DarwinTrainStatusFrame,
            DarwinScheduleChangeFrame, DarwinLocationRef, DarwinServiceBoardRow(v2),
            NationalRailBoardDeparture
model/      NationalRailServiceRow, NationalRailCallingPointRow, NationalRailScheduleRecord
policy/     NationalRailBoardKeys       lineId + direction (BACKEND-COUPLED)
util/       NationalRailFcmTopic, NationalRailTime
config/     NationalRailProperties
README.md
```

**Also modified (shared):** `model/Station.java` (+`crs`),
`resources/application.properties` (+`nationalrail.*` / `darwin.*`).

**Real vs stub:** the entire engine is real — SQL schema + baseline replace +
delta application (TS timing/platform/cancel *and* SC cancel/re-plan) + the board
query + day-rollover + TIPLOC→CRS→naptan resolution + coverage + debounce +
content-diff + push. Only the **four wire clients** return empty pending
credentials + protocol code.

---

## 4. Data flow

**Baseline (daily 03:00 + startup):** `NationalRailTimetableLoadScheduler` →
`NationalRailTimetableService.loadBaselineForToday()` → mapping.refresh() →
`DarwinTimetableFeedClient.fetchTimetable()` → resolve TIPLOC→CRS + times→ms +
day rollover → `repository.replaceBaselineForDate()` → purge yesterday.

**Live:** `DarwinPushPortClient` → listener → `NationalRailDeltaApplier`
(`applyTimingUpdate`/`applyScheduleChange`, covered-only) mutates SQL and returns
impacted covered naptanIds → `NationalRailRefreshDebouncer.schedule()` →
`NationalRailStationRefreshService.refresh()` → `NationalRailBoardEngine.buildBoard()`
(SQL) → change detector → `NationalRailPushNotifier` → `Station_<naptanId>`.

**Heartbeat (15m):** schedules every covered station through the same debounced
SQL-only refresh path (no external call).

---

## 5. Integration contracts (verified against the app)

- **Topic** `Station_<naptanId>` (`normalize` = uppercase + `[^A-Z0-9-_.~%]→~`);
  match = `selection.station` OR `payload.id`.
- **Payload** `data["payload"]` = JSON of `StationPredictions` (== `FcmPayload`).
- **⚠️ line/direction keys are backend-coupled** — `payload.lines[selection.line]`
  + `dirs[selection.direction]` mismatch → **empty board**. `NationalRailBoardKeys`
  defaults (single `national-rail` line + `outbound`) MUST match backend NR modeling.
- **eta = ISO-8601 instant** (app derives countdown); cancelled services are
  excluded by the board query (no cancelled state in `PredictionItem`).

---

## 6. Pre-work before go-live

1. **Implement the 4 stubs** (§3). Kafka dep (`spring-kafka`) already in `pom.xml`.
2. **Darwin credentials** (§7) → env → `NATIONALRAIL_ENABLED=true`.
3. **stationly-backend:** `DarwinPredictionSource` for the cold-open board —
   ideally reads this **same SQL mirror** via an internal syncer endpoint (decide
   shared-DB vs endpoint), else calls LDBWS. Placeholder in `PredictionSourceFactory`.
4. **TfL station sync:** populate `Station.crs` from StopPoint `additionalProperties`
   + add `national-rail` to affected stations' modes.
5. **Finalise `NationalRailBoardKeys`** against the backend line/direction model.

### Known v1 limitations (documented, acceptable for first cut)
- **Associations (splits/joins)** not modeled → portion-working services show a
  single last-calling-point destination. v2.
- **Newly-covered station** has baseline rows but missed that morning's deltas
  until each service gets a fresh TS (frequent on NR) — v2 LDBWS re-anchor closes it.
- **Circular services** (same TIPLOC twice) collapse — `(rid,tiploc)` PK; rare on NR boards.
- **Late-night TS rollover** across midnight uses ssd without per-row scheduled
  cross-check — rare edge.

---

## 7. Darwin registration & keys

Register at the **Rail Data Marketplace** — https://raildata.org.uk (one org
account). Subscribe to the products below; copy credentials into env (all wired
in `application.properties`; none committed). Separate from the TfL key.

**Only TWO products needed** (both subscribed as of 2026-07-21):

| RDM product | Type | Gives | Env |
|---|---|---|---|
| **Darwin Real Time Train Information** | Pub/Sub (**Kafka + JSON**, Darwin v17) | live TS/SC deltas. Pub/Sub tab → bootstrap servers, consumer key/secret (SASL), group, topic | `DARWIN_KAFKA_BOOTSTRAP_SERVERS/_CONSUMER_KEY/_CONSUMER_SECRET/_GROUP_ID/_TOPIC` |
| **Darwin Timetable Files** | File (OGL3) | daily base timetable (SQL baseline) **+** reference/location file (TIPLOC↔CRS↔name) — check the product's *Data files* tab | `DARWIN_TIMETABLE_FEED_*` and `DARWIN_REFERENCE_DATA_*` |
| — | — | flips the module on | `NATIONALRAIL_ENABLED=true` |

- **Live feed is Kafka, not STOMP.** RDM's Darwin real-time is a Kafka stream of
  schemaless JSON (Darwin v17) — the legacy STOMP/ActiveMQ + gzipped-XML Push Port
  is NOT what RDM serves. `DarwinPushPortClient` is a Kafka consumer (spring-kafka,
  SASL); ref client: `openraildata/kafka-client-rdm-darwin`. Message JSON schema is
  on the product's *Documentation* tab — needed to finish the parser.
- **Timetable Files** is a static file product (base timetable from Network Rail
  CIF, includes schedule changes/cancellations/associations as of generation, NO
  live times). The reference/location file (TIPLOC↔CRS) is normally in the same
  product's *Data files* tab; if not, source it separately.
- **OpenLDBWS** — only if/when v2 reconciliation is built; not subscribed.
- Both products are free; the Kafka feed is a national firehose — we filter to
  covered stations on ingest.

---

## 8. Run / verify

```bash
cd StationlySyncer
mvn compile                                    # BUILD SUCCESS (module off, stubs empty)
NATIONALRAIL_ENABLED=true mvn spring-boot:run  # creates data/stationly-nationalrail.sqlite,
                                               # runs 03:00-style baseline (0 rows, stub feed),
                                               # heartbeat + listener wiring active
```
Best first unit tests (pure): `NationalRailScheduleRepositoryImpl` (baseline →
delta → board query round-trip on a temp SQLite), `NationalRailTimetableServiceImpl`
(day-rollover + TIPLOC filter), `NationalRailBoardEngine` (row → payload),
`NationalRailStationMappingServiceImpl`. **No tests yet. Do NOT deploy to staging.**

---

## 9. Git state

Committed 2026-07-25 on **`dev_national_rail_integration`**, branched from `main`:

```
 M pom.xml                                                    (+spring-kafka)
 M src/main/java/com/stationly/backend/model/Station.java     (+crs)
 M src/main/resources/application.properties                  (+nationalrail.*/darwin.*)
 A src/main/java/com/stationly/backend/nationalrail/          (module, 36 files)
 A docs/NATIONAL_RAIL_DARWIN_HANDOVER.md
```

No secrets committed — every `darwin.*` property is an env placeholder with an
empty default. The matching backend branch of the same name carries the station
seeder and `docs/NATIONAL_RAIL_HANDOVER.md`.

---

## 10. Gotchas

- **`NationalRailBoardKeys` empty-board trap** — likeliest "shows nothing" cause; keep keys matched to backend selection.
- **Apply SC, not just TS** — TS-only mirror drifts wrong by mid-day.
- **Covered-only delta apply** — the deliberate CPU saver on the national firehose; full baseline is loaded regardless (storage is cheap).
- **Board content-diff is the authoritative push gate**; times stored as **epoch ms** so ordering/"future only" are plain comparisons, formatted to ISO only at payload build.
- **In-memory** change-detector/mapping/coverage — restart ⇒ at most one redundant push per station; the SQL mirror itself is durable.
- **FCM topic format** duplicated in `NationalRailFcmTopic` — keep in lockstep with `DataTransformationService`.
```
