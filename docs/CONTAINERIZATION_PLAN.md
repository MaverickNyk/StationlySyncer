# Containerization Plan — StationlySyncer

Goal: run the syncer as a Docker container using the same pattern as
`stationly-admin`: one config-free image pushed to GHCR, per-VM `.env` for
secrets, `docker compose` on the VM, manual `workflow_dispatch` deploy that
pulls + runs the already-pushed tag. The properties-merge shell scripts
(`staging_deploy.sh`, the merge step in `deploy-prod.yml`) go away entirely —
the app already reads everything from env vars (`${TFL_APP_KEY:...}` etc. in
`application.properties`), so it is 12-factor ready as-is.

Key difference from stationly-admin: the syncer is a headless worker
(`spring.main.web-application-type=none`) — no HTTP port to publish and no
URL to health-check. It also has local state (SQLite mirror) and a
file-based secret (Firebase service account JSON), both handled with mounts.

## 1. Dockerfile (multi-stage, like admin's deps → build → runtime)

```dockerfile
# --- 1. build: compile the fat jar with a cached dependency layer ------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# --- 2. runtime: minimal JRE, non-root ---------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app \
 && mkdir -p /app/data && chown app:app /app/data
COPY --from=build /app/target/stationly_syncer-*.jar app.jar
USER app
# SQLite lives on the mounted volume; service-account JSON is mounted read-only.
ENV SQLITE_DB_PATH=/app/data/stationly-syncer.sqlite \
    FCM_SERVICE_ACCOUNT_PATH=/secrets/firebase-service-account.json
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Notes:
- Align Java: pom says `java.version=17` but prod CI builds with 21. Bump the
  pom to 21 (or build the image with 17) so image and pom agree.
- The exclude of macOS-only `netty-resolver-dns-native-macos` is harmless in
  the image (classifier won't load on linux), no change needed.

## 2. .dockerignore (mirror admin's "small context, secret-free" rules)

```
target
data
*.sqlite
serviceAccountKey.json
src/main/resources/application-local.properties
local_scripts
server-config
.env
.env.*
.git
.github
.gitignore
graphify-out
screenshots
docs
*.md
.DS_Store
```

## 3. docker-compose.yml (per VM, next to a gitignored .env)

```yaml
services:
  syncer:
    image: ghcr.io/mavericknyk/stationly-syncer:${SYNCER_TAG:?SYNCER_TAG must be set (tag from publish_image.sh)}
    restart: unless-stopped
    env_file: .env            # TFL_APP_KEY, FIRESTORE_PROJECT_ID, TFL_POLLING_*, ...
    volumes:
      - ./data:/app/data      # SQLite mirror survives redeploys
      - ./firebase-service-account.json:/secrets/firebase-service-account.json:ro
    # Headless worker — no ports. Healthcheck is process-level; see below for a
    # better app-level option.
    healthcheck:
      test: ["CMD", "pgrep", "-f", "app.jar"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 30s
```

Optional improvement: have the app touch `/app/data/heartbeat` each polling
cycle and healthcheck `find /app/data/heartbeat -mmin -2`, which catches a
hung poller, not just a dead JVM.

## 4. Publish + deploy (copy admin's flow)

1. `publish_image.sh`: build + push `ghcr.io/mavericknyk/stationly-syncer`
   with a `YYYYMMDD-HHMM-<sha>` tag (same tag format admin uses).
2. Rewrite `.github/workflows/deploy-prod.yml` as a `workflow_dispatch`
   deploy like admin's `deploy.yml`:
   - inputs: `environment` (staging/prod) + `tag`; `concurrency` per env;
     `environment: ${{ inputs.environment }}` for secret scoping/approvals.
   - SSH step (appleboy/ssh-action pinned to a SHA):
     `cd ~/stationly-syncer && export SYNCER_TAG=<tag> && docker compose pull && docker compose up -d`
   - health check: `docker inspect --format='{{.State.Health.Status}}'` is
     `healthy`, since there's no HTTP endpoint to curl.
3. Delete the properties-merge step, the `prod.env` dummy step, and
   `local_scripts/staging_deploy.sh` (staging deploys go through the same
   workflow with `environment: staging`).

## 5. One-time VM migration (per VM, staging first)

1. Create `~/stationly-syncer/` with `docker-compose.yml`, `.env`
   (chmod 600), and `firebase-service-account.json` (chmod 600) — values come
   from the current `~/config/application.properties`.
2. Copy the existing SQLite db into `~/stationly-syncer/data/` so the
   watermark/subscribed-station state carries over (avoids a NAP-mode cold
   start).
3. `SYNCER_TAG=<tag> docker compose up -d`, verify logs show Firestore +
   FCM init and polling.
4. `sudo systemctl disable --now stationly.service` and remove the old jar.

## Order of work

1. Dockerfile + .dockerignore + compose, verified locally with a stub `.env`.
2. publish_image.sh + GHCR auth.
3. New deploy workflow (staging path) → migrate staging VM → soak.
4. Migrate prod VM, retire systemd unit and old workflow steps.
