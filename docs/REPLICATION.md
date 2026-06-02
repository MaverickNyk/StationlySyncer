# StationlySyncer — Replication & Watermark Notes

> Read `stationly-backend/docs/REPLICATION.md` first — it's the canonical
> description of the master-slave model and the integer `lastUpdatedTime`
> watermark. This file covers what is **syncer-specific**.

## Role

The syncer is the TfL ingestion engine. It:
- polls TfL for **predictions** (every `tfl.polling.interval`, 30 s) → transforms
  → publishes via **FCM** (and writes the local prediction cache);
- polls TfL for **line status** (every `tfl.status.polling.interval`, **10 min**)
  → **change-detects** → writes **only changed** statuses to Firestore +
  publishes FCM;
- maintains a local SQLite replica of `stations` and `lineStatuses`, and a
  durable mirror of `metadata/subscribed_stations`.

It replicates **only**: `stations`, `lineStatuses`, and
`metadata/subscribed_stations`. (Backend replicates the full reference set.)

## Watermark = epoch millis (integer), read **leniently**

Same contract as the backend: `lastUpdatedTime` is an integer.

Because Firestore `toObject` is strict, the model watermark fields are typed
**`Object`** (not `String`) so deserialization tolerates **both** legacy ISO
strings and post-migration integers across the cutover and for any
not-yet-migrated docs (e.g. the `stations` tail):

- `Station.lastUpdatedTime : Object`
- `LineStatusResponse.lastUpdatedTime : Object`

Always coerce with **`TimeUtils.toEpochMs(Object)`** (handles number /
epoch-string / ISO / legacy no-`Z`). Compare and store as `long`. Writers set
`TimeUtils.nowMs()` (epoch millis) — **never** `LocalDateTime.now()` (that was
the broken local/no-`Z`/nanosecond `stations` format, fixed in
`StationService.mergeLineInfoIntoStation`).

### Queries + checkpoint

- Firestore delta/listener queries pass a **`long`**:
  `whereGreaterThan("lastUpdatedTime", TimeUtils.toEpochMs(lastSync))`.
  During the cutover window (field still string) a Long query returns nothing →
  the syncer serves from SQLite; once migrated, deltas flow.
- The checkpoint (`sync_metadata`) is stored as an epoch string and advanced
  with the **atomic + `CAST(... AS INTEGER)`** upsert in
  `LocalDatabaseServiceImpl.updateLastSyncTime`. The CAST is mandatory — a
  lexical compare freezes an epoch checkpoint behind a legacy ISO one.

## `subscribed_stations` — RAM-first, SQLite mirror, never-miss

This drives which stations we poll (subscribed strategy). Design:

- **`FirestoreDatabaseSyncer`** holds the live `metadata/subscribed_stations`
  doc in RAM (realtime listener) **and** mirrors `stationCounts` →
  the `subscribed_stations` SQLite table on every update (master→slave), plus
  advances a `subscribed_stations` checkpoint. It does **not** wipe the mirror
  on a transient document-removed event (never-miss).
- **`TflPollingService.getActiveSubscribedStations`** reads **RAM first** (always
  in sync), and falls back to the **SQLite mirror only when RAM is cold** (just
  restarted, before the listener's first snapshot). This closes the gap where a
  fresh restart would otherwise return an empty set → **NAP MODE → zero
  predictions** until the listener warmed.
- SQLite swap is atomic (clear+insert in one transaction) so a concurrent
  cold-start read never sees a half-populated set.

## Gotchas

- `getConnection()` opens a **new** SQLite connection per call. The atomic
  checkpoint upsert keeps it to one statement; don't reintroduce a
  read-then-write (it would double the connection churn and add a race).
- The first restart after adding the `subscribed_stations` table sees an empty
  mirror (table just created) → the usual brief warm-up window applies; from the
  second restart on, the mirror is populated.
- Change-detection for line status compares severity + reason (not the
  watermark); only changed statuses are written to Firestore + FCM. Keep it that
  way — it's the core minimal-write guarantee.
