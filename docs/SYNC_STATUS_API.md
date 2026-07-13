# Sync Status API & Telemetry

The Syncer is otherwise a headless worker (no public API). To make it observable
the same way the rest of the platform is — so **stationly-admin** can show whether
syncing is actually healthy instead of *inferring* it from backend data freshness —
it now:

1. Logs **one row per sync run** into SQLite, written **asynchronously** so it can
   never slow down or fail the sync itself.
2. Serves a small **reactive (Netty) HTTP API** with those results.
3. Runs a **retention/rollup sweep** so the table can't grow unbounded (arrivals
   sync every ~30s ⇒ ~2,880 rows/day).

```
sync loops ──record()──► in-mem queue ──daemon writer──► SQLite (sync_run)
 (never blocks)                                              │
                                          retention sweep ───┤ raw → hourly → daily → delete
                                                             ▼
                              Netty :8081  /health  /sync-status  /sync-status/runs  /sync-status/rollup
                                                             ▲
                                              stationly-admin health dashboard
```

## Endpoints

| Method · Path | Purpose | Auth |
|---|---|---|
| `GET /health` | Compact liveness — `200` (up/degraded) or `503` (down). For probes/load-balancers. | always open |
| `GET /sync-status` | Full dashboard: overall status + latest run per job + last-1h/24h aggregates + fcm/writer/retention stats. | key (if set) |
| `GET /sync-status/runs?job=&before=&limit=` | Recent raw per-cycle rows (paginate with `before=<startedAt ms>`, `limit`≤500). | key (if set) |
| `GET /sync-status/rollup?bucket=hour\|day&job=&since=&until=` | Historical aggregates for charts. | key (if set) |

`job` ∈ `arrivals` · `line_status` · `station_sync`.

### Health semantics (driven by the **arrivals** loop — the ~30s heartbeat)

- **down** — no cycle since boot (past a 2× grace), or last cycle older than **3× the
  poll interval** (the scheduler/JVM is stuck). Returns HTTP `503`.
- **degraded** — latest cycle was `partial`/`failed`, or FCM is disabled while
  `strategy=subscribed`. Returns HTTP `200` (it's alive, just unhealthy).
- **up** — cycling normally. **NAP is healthy**: an idle Syncer with no subscribed
  stations still beats every 30s and reports `up` + `napMode:true` (this is the
  signal the old backend-inference approach could not tell apart from "dead").

## Configuration (`application.properties` / env)

| Property | Env | Default | Notes |
|---|---|---|---|
| `server.port` | `SYNCER_STATUS_PORT` | `8081` | Status API port. |
| `server.address` | `SYNCER_STATUS_BIND` | `127.0.0.1` | **Localhost by default** — not publicly exposed. |
| `syncer.status.key` | `SYNCER_STATUS_KEY` | *(unset)* | Bearer key gating `/sync-status*` (`Authorization: Bearer <key>` or `X-Stationly-Key`). `/health` stays open. |
| `syncer.status.queue-capacity` | `SYNCER_STATUS_QUEUE` | `2000` | Async writer buffer; full ⇒ records dropped (counted), never blocks a sync. |
| `syncer.status.write-batch` | `SYNCER_STATUS_BATCH` | `200` | Max rows per write transaction. |
| `syncer.status.raw-retention` | `SYNCER_STATUS_RAW_RETENTION` | `24h` | Raw per-run rows kept this long, then rolled into hourly. |
| `syncer.status.hourly-retention` | `SYNCER_STATUS_HOURLY_RETENTION` | `30d` | Hourly buckets kept then rolled into daily. |
| `syncer.status.daily-retention` | `SYNCER_STATUS_DAILY_RETENTION` | `365d` | Daily buckets kept then deleted. |
| `syncer.status.rollup-cron` | `SYNCER_STATUS_ROLLUP_CRON` | `0 5 * * * *` | When the sweep runs (Spring cron; default :05 each hour). |

## Data model & retention

Two tables in the existing `data/stationly-syncer.sqlite` (own connections, **WAL +
busy-timeout** so they don't lock against the existing line-status/station writers):

- **`sync_run`** — one raw row per cycle: job, timings, status (`ok|partial|failed|nap`),
  cycle #, NAP flag, subscription count, per-mode counts, errors, and a `detail_json`
  with the rich per-mode breakdown.
- **`sync_run_rollup`** — `hour` / `day` aggregates (counts by status, sum/avg/max
  duration, summed arrivals/fcm/changed/errors).

The hourly sweep (one transaction): roll raw rows older than `raw-retention` into
**hourly** buckets and delete them → roll hourly older than `hourly-retention` into
**daily** and delete them → delete daily older than `daily-retention`. Each raw row is
rolled up exactly once; an additive `ON CONFLICT` upsert keeps partially-aged buckets
correct. A sweep also runs once on boot.

## Deployment

- **systemd is unchanged** — same JAR. It now also binds `127.0.0.1:8081`. Nothing
  else on the staging VM uses 8081 (backend `:8080`, admin `:4000`).
- **Same-VM admin** (staging shares the VM): admin probes `http://127.0.0.1:8081/health`
  directly — no nginx, no TLS, no key needed.
- **Cross-VM / public** (e.g. prod Syncer on its own host): keep the localhost bind and
  put the host's nginx in front with a key, e.g. add to the existing
  `api.stationly.co.uk` server block:

  ```nginx
  # Syncer status — gate with syncer.status.key and/or Cloudflare Access / an IP allowlist.
  location /syncer/ {
      proxy_pass http://127.0.0.1:8081/;     # /syncer/health → :8081/health
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
  }
  ```

  Only set `SYNCER_STATUS_BIND=0.0.0.0` if a **remote** client must reach the port
  directly (no local proxy) — then a key is mandatory.

## stationly-admin integration (follow-up, separate repo)

`stationly-admin/lib/health/checks.ts :: inferSyncer()` currently *guesses* Syncer
health from backend cache presence + line-status freshness. Replace that with a direct
probe of `GET /health` (and surface `/sync-status` on a Syncer panel). Send the key as
`Authorization: Bearer ${SYNCER_STATUS_KEY}` if one is configured.
