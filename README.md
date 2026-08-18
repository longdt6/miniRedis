# miniRedis

A tiny in-memory key-value store with TTL, JSON snapshotting, and a browser UI.
Built with Java 21 + Quarkus 3.x, compiled to a **GraalVM native image** for ~50ms cold start.

## Features

- HTTP/JSON API on `POST /cmd` (SET, GET, DEL, EXISTS, INCR, KEYS, FLUSHALL, EXPIRE, TTL, PING)
- Lazy + active expiry (check on read + scheduled sweep)
- Atomic JSON snapshot at shutdown, loaded at boot (`Files.move(..., ATOMIC_MOVE)`)
- Vanilla HTML/JS UI served at `/`
- GraalVM native-image via Mandrel — single static binary, ~15MB RSS

## Project layout

```
src/main/java/com/example/miniredis/
├── store/   Item.java · Store.java · SnapshotCodec.java
├── cmd/     Reply.java · CommandHandler.java · CommandDispatcher.java · StringCommands.java · ExpiryCommands.java
├── web/     CmdResource.java · ReplyWriter.java
└── lifecycle/ SnapshotOnShutdown.java · ExpiryTicker.java
src/main/resources/
├── application.properties
└── META-INF/resources/index.html
```

## Run locally — JVM mode (fastest iteration)

```bash
./mvnw quarkus:dev
# open http://localhost:8080
# Ctrl-C triggers graceful shutdown → snapshot written to ./data/miniredis.json
```

## Run locally — native mode (the point of the stack)

Requires a local GraalVM / Mandrel install. With Mandrel JDK 21:

```bash
./mvnw package -Dnative
./target/miniredis-runner
```

Cold start should be ~50ms. `ps aux | grep miniredis-runner` should show ~15MB RSS.

## Tests

```bash
./mvnw test
```

39 tests across `Store`, `CommandDispatcher`, `CmdResource` (REST Assured), and `SnapshotCodec`.

## Deploy to Render (GraalVM native via Docker)

The included `Dockerfile` is a two-stage build:

1. **Build stage** uses `quay.io/quarkus/mandrel-builder-image:jdk-21`, which ships Mandrel (GraalVM) pre-installed. It runs `./mvnw package -Dnative` and produces a single static binary at `target/miniredis-runner`.
2. **Runtime stage** uses `quay.io/quarkus/quarkus-micro-image:2.0` (~50MB base) and copies only the binary — no JVM, no JDK, no classpath.

### Steps

1. Push this repo to GitHub.
2. In the Render dashboard: **New → Blueprint**, connect the repo. Render detects `render.yaml` and provisions the service.
3. Render will:
   - Pull the Dockerfile
   - Run stage 1 (native compilation — typically 2–4 min)
   - Run stage 2 (tiny image with the binary)
   - Expose the service on the public URL
4. Visit `https://<your-service>.onrender.com/` — the UI is live.

### Free tier caveat

Render's **free** plan does not run Docker builds (builds use a `pulse` service on the free plan and don't support Docker runtime in all regions). The included `render.yaml` declares `plan: starter` ($7/mo), which is the cheapest plan with Docker runtime.

**If you only have access to the free plan**, switch to JVM mode by editing the service settings:

- Build Command: `./mvnw package -DskipTests`
- Start Command: `java -jar target/quarkus-app/quarkus-run.jar`

Cold start becomes ~1s instead of ~50ms, but the app behaves identically.

### Ephemeral filesystem

Render instances have ephemeral disks. The snapshot at `/tmp/miniredis.json` survives process restarts but is wiped on redeploy. For a demo this is fine; for durability, swap the snapshot codec for a write-ahead log or push to Redis/S3.

## Configuration

All knobs are environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP listen port |
| `MINIREDIS_DATA_FILE` | `./data/miniredis.json` | JSON snapshot location |
| `MINIREDIS_EXPIRY_SWEEP_MS` | `1000` | Active-expiry sweep interval |
| `QUARKUS_SHUTDOWN_TIMEOUT` | `5s` | Drain window for in-flight requests |

## Architecture in one diagram

```
Browser ──HTTP/JSON──▶ CmdResource ──▶ CommandDispatcher ──▶ Store ──▶ SnapshotCodec ──▶ data/*.json
                                          (dispatch table)      (ConcurrentHashMap + expiry)
```
