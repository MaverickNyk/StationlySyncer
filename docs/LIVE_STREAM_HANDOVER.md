# Live stream publishing (Syncer side) — handover

**Status: deployed to staging and verified end-to-end (2026-07-31). Not committed.**

The Syncer now dispatches change-detected station predictions **and line statuses** to the Node backend, which fans them out over WebSocket to app clients with a board on screen.

Full picture (protocol, cache, nginx, security) lives in
`stationly-backend/docs/LIVE_STREAM_HANDOVER.md`. This file covers only what changed here.

---

## What changed

| File | Change |
|---|---|
| `service/LiveStreamPublisher.java` | **New.** Debounced queue + daemon pacer, POSTs to the backend. Now carries **two** channels — stations and line statuses — on separate queues to separate endpoints |
| `service/TflPollingService.java` | One field + one call after `fcmService.publishAll(fcmData)` |
| `service/LineService.java` | **Firestore + SQLite removed entirely** (see below). Now pushes changed statuses to the backend, and suppresses FCM on the first sync after boot |
| `config/RepositoryConfig.java` | `lineStatusRepository` bean deleted — nothing persists statuses any more |
| `resources/application.properties` | New `livestream.*` block |
| `resources/application-remote.properties` | `livestream.ingest-secret=` (**must stay blank** — see below) |

```java
fcmService.publishAll(fcmData);          // existing — untouched
liveStreamPublisher.publishAll(fcmData); // new — same map, second consumer
```

Both consume the **same** `ChangeDetectionService` output, so the stream updates on exactly the cadence FCM does, with no extra TfL work and no second change-detection pass.

---

## Line status: zero Firestore, zero SQLite

Line statuses used to be written to Firestore (`lineStatusRepository.saveAll`) and mirrored to SQLite, with a boot delta-sync and a live `onSnapshot` listener reading them back. All of that is gone on both sides.

**Why the listener could go:** it existed to pick up statuses written by the *Node backend's* tier-4 TfL refresh. That write was removed, leaving the listener reading back only this service's own writes — a Firestore document read per change, forever, for no data.

The flow is now:

```
@Scheduled → TfL fetch → change-detect (unchanged)
                            ├──► fcmService.publishAll()            (unchanged)
                            └──► liveStreamPublisher.publishLineStatuses()
                                     │  POST /internal/line-status-updates
                                     ▼
                            backend DataCacheService.setLineStatus()
                                     ├──► in-memory Map (the only store)
                                     └──► WebSocket subscribers
```

`lineStatusesCache` is now the **only** store and therefore the only change-detection baseline.

### The `primed` flag — do not remove it

With nothing persisted, the baseline starts empty, so the first poll after **every restart and every deploy** marks all ~30 lines as changed. Left alone that pushes a "status changed" notification to every subscribed user for changes that never happened.

`primed` suppresses **FCM only** on the first sync. Two details that are easy to get wrong:

1. The **stream push is deliberately not suppressed** — the backend's cache is empty at that moment and needs current state, and a stream update only refreshes what a client already displays rather than interrupting anyone.
2. It is set **only when `allStatuses` is non-empty**. If TfL was unreachable for every mode, the cache is still cold; flipping the flag would let the next healthy sync fire the exact storm it exists to prevent. Both are covered by tests in `LineServiceTest`.

### This dispatch is now load-bearing

For stations, the POST is a second path alongside FCM. For line statuses it is the **only** live path to the backend. If it breaks, the backend falls back to polling TfL itself once per mode per 60s — so nothing looks broken while clients sit up to a minute stale.

Watch `lines.writes.syncer` at the backend's `GET /internal/stream-stats`. A flat counter there is the only signal that this dispatch has stopped.

---

## Design constraints (do not break these)

**Strictly additive.** If the backend is down, TfL polling and FCM must be unaffected. Three properties enforce that:

1. **`publishAll` only touches a `ConcurrentHashMap`.** `@Scheduled` uses a single-threaded scheduler, so *any* blocking here directly delays the next TfL poll. Keep it enqueue-only.
2. **Dispatch is on a dedicated daemon thread and never blocks.** The `WebClient` call is `.subscribe()`d with a per-request `.timeout()` — **never `.block()`**.
3. **Failures are logged and dropped, never retried.** A dropped batch is superseded by the next poll cycle ~30s later, which is far cheaper than backing up behind a wedged backend.

Mirrors `FcmService`'s shape deliberately, including **upsert/debounce semantics** — if the pacer falls behind, a station's newer payload replaces the pending one rather than queueing both. Stale departure data is worth less than nothing.

---

## Two traps

**Do NOT implement `NotificationService`.** `LineService` injects that interface by type (`private final NotificationService fcmService`). A second implementation makes Spring fail its constructor injection with `NoUniqueBeanDefinitionException`. `LiveStreamPublisher` deliberately implements nothing.

**`.clone()` the `WebClient.Builder`.** `WebClientConfig` exposes a **singleton** builder bean (not the auto-configured prototype one). Calling `.defaultHeader(...)` on it directly would mutate the shared bean and leak the ingest secret onto every TfL API call made by `TflApiClient`.

---

## Naming

Originally `WebSocketNotifier`; renamed to **`LiveStreamPublisher`**. The Syncer speaks **HTTP**, not WebSocket — the socket is the backend's downstream transport and none of this service's business. Naming a class after a protocol it doesn't speak would go stale the moment that transport changed. (`FcmService` is correctly named, because it really does talk to FCM.)

---

## Config

```properties
livestream.enabled=${LIVESTREAM_ENABLED:true}
livestream.backend-url=${LIVESTREAM_BACKEND_URL:http://127.0.0.1:3000}
livestream.ingest-secret=${LIVESTREAM_INGEST_SECRET:}
livestream.timeout=${LIVESTREAM_TIMEOUT:2}
```

Node runs on the **same host**, so this stays on loopback and never crosses nginx.

A blank secret **disables the publisher entirely** (fail-closed) rather than logging a failure every 500ms — the backend rejects unauthenticated ingest with 503. Look for this on startup:

```
🔌 Live-stream publisher started (target: http://127.0.0.1:3000)
```

If you instead see `⚠️ livestream.ingest-secret not set`, the secret didn't reach the server.

---

## Deploying (the part that catches people)

**`application-remote.properties` is a MANIFEST OF KEY NAMES, not values.** `local_scripts/staging_deploy.sh` copies `application.properties` as the base, then for each key in `application-remote.properties` looks up `STAGING_<KEY>` **in the environment**. The value in that file is never read.

So writing a real secret there:
- **leaks it** — the file is git-tracked, and
- **doesn't deploy** — the merge loop ignores it.

Correct:

```bash
export STAGING_LIVESTREAM_INGEST_SECRET=$(grep '^LIVESTREAM_INGEST_SECRET=' ../stationly-backend/.env | cut -d= -f2-)
bash local_scripts/staging_deploy.sh
```

Sourcing from the backend's `.env` guarantees both sides match — a mismatch means every ingest is rejected. For **prod**, add the matching GitHub secret and expose it in `deploy-prod.yml`'s `env:` block, or the merge loop silently skips it.

Also note: a key **absent** from `application-remote.properties` is silently skipped, which is why the blank line itself matters.

---

## Verifying

```bash
sudo journalctl -u stationly.service -f | grep -E "Live-stream|\[WS\]"
```

- `🔌 Live-stream publisher started` — good
- `⚠️ [WS] Dispatch failed` — backend down or secret mismatch. **FCM and polling are unaffected**; this is the additive path failing safely.

End-to-end from your machine:

```bash
cd ../stationly-backend && node .scripts/watch_stream.mjs 910GHTCHEND
```

Should show a snapshot immediately, then live updates roughly every 30s.

---

## Outstanding

- **Line status is NOT streamed.** Investigated and deferred: it has two producers (this Syncer *and* the backend's own on-demand TfL refresh), polls every 10min, and already reaches the backend via Firestore `onSnapshot`. See the backend handover for the full reasoning.
- **`LiveStreamPublisher` has no tests.** Verified manually end-to-end with real TfL data.
- **Nothing is committed.**
