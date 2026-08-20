# miniRedis

A tiny in-memory key-value store, built to **teach how Redis works on the inside**.
It implements a small slice of Redis — strings, TTL, and persistence — using the same
mechanisms the real thing uses, then exposes it over a browser UI so you can watch it run.

Built with Java 21 + Quarkus 3.x, compiled to a **GraalVM native image** for ~50ms cold start.

**Live demo:** [https://miniredis-lf1r.onrender.com/](https://miniredis-lf1r.onrender.com/)

---

## What this project is for

miniRedis is a **learning vehicle**, not a production database. Its goal is to make the
foundational ideas of Redis concrete enough to read in a few hundred lines of Java. Every
design decision it makes mirrors one Redis actually makes — and the code comments call out
where that happens, so you can read the *why*, not just the *how*.

It is **not** a full Redis: no RESP wire protocol, no lists/sets/sorted-sets/hashes/streams,
no replication, Sentinel, or clustering. It covers the first two chapters well, on purpose.

## Foundation topics covered

| Topic | What you learn | Where |
|---|---|---|
| **Single-threaded execution** | All commands serialize onto one worker thread — the single most important Redis mental model, and the reason its commands are race-free | `store/Store.java` |
| **In-memory data model** | A string→value hash map is the core; everything else is built on it | `store/Store.java` |
| **Command dispatch** | A `name → handler` map, exactly how Redis registers commands | `cmd/CommandDispatcher.java` |
| **Strings data type** | `SET GET DEL EXISTS INCR KEYS FLUSHALL` | `cmd/StringCommands.java` |
| **TTL & expiry** | Two-pronged design: **lazy** (check on read) + **active** (scheduled sweep) | `store/Store.java` · `lifecycle/ExpiryTicker.java` |
| **Reply type system** | Simple / Int / Bulk / Array / Error — mirrors RESP's type taxonomy | `cmd/Reply.java` |
| **Persistence — snapshot** | "Dump final state" (RDB-style), written atomically via `ATOMIC_MOVE` | `store/SnapshotCodec.java` |
| **Persistence — AOF** | Append-only command log: **canonicalization**, **fsync policy** (`always` vs `everysec`), **truncated-tail recovery** | `store/AofLog.java` · `store/AofReplay.java` |
| **Two-tier durability** | Snapshot + AOF replay on boot, and how to keep the pair consistent under crash | `lifecycle/PersistenceLifecycle.java` |

The AOF code is the richest part: relative `EXPIRE key 100` is never logged verbatim — it is
canonicalized to an absolute `PEXPIREAT key <epoch-ms>` so a replay after restart still expires
at the right instant. Each of these subtleties is annotated directly in the source.

## Features

- HTTP/JSON API on `POST /cmd` — `PING SET GET DEL EXISTS INCR KEYS FLUSHALL EXPIRE TTL PEXPIREAT`
- Lazy + active expiry (check on read + scheduled sweep)
- **AOF** (append-only command log) with configurable fsync policy, replayed at boot
- **JSON snapshot** written atomically at shutdown, loaded at boot
- **Two-tier persistence**: snapshot holds the bulk, AOF holds the tail since the last snapshot
- Vanilla HTML/JS browser UI at `/`
- GraalVM native-image via Mandrel — single static binary, ~15MB RSS

## Project layout

```
src/main/java/com/example/miniredis/
├── store/     Item.java · Store.java · SnapshotCodec.java · AofLog.java · AofReplay.java
├── cmd/       Reply.java · CommandHandler.java · CommandDispatcher.java · StringCommands.java · ExpiryCommands.java
├── web/       CmdResource.java · ReplyWriter.java
└── lifecycle/ PersistenceLifecycle.java · ExpiryTicker.java
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

Tests cover `Store`, `CommandDispatcher`, `CmdResource` (REST Assured), `SnapshotCodec`,
`AofLog`, `AofReplay`, and store concurrency. Test runs are hermetic: AOF is off and data
files live under `target/` (gitignored).

---

## Deployment

### Render (GraalVM native via Docker)

The included `Dockerfile` is a two-stage build:

1. **Build stage** uses `quay.io/quarkus/mandrel-builder-image:jdk-21`, which ships Mandrel
   (GraalVM) pre-installed. It runs `./mvnw package -Dnative` and produces a single static
   binary at `target/miniredis-runner`.
2. **Runtime stage** uses `quay.io/quarkus/quarkus-micro-image:2.0` (~50MB base) and copies
   only the binary — no JVM, no JDK, no classpath.

#### Steps

1. Push this repo to GitHub.
2. In the Render dashboard: **New → Blueprint**, connect the repo. Render detects `render.yaml`
   and provisions the service.
3. Render will pull the Dockerfile, run stage 1 (native compilation, typically 2–4 min), run
   stage 2 (tiny image with the binary), and expose the service on a public URL.
4. Visit `https://<your-service>.onrender.com/` — the UI is live.

#### Free-tier caveat

Render's **free** plan does not run Docker builds. The included `render.yaml` declares
`plan: starter` ($7/mo), the cheapest plan with Docker runtime.

**If you only have the free plan**, switch to JVM mode by editing the service settings:

- Build Command: `./mvnw package -DskipTests`
- Start Command: `java -jar target/quarkus-app/quarkus-run.jar`

Cold start becomes ~1s instead of ~50ms, but the app behaves identically.

#### Ephemeral filesystem

Render instances have ephemeral disks. The snapshot and AOF under `/tmp` survive process
restarts but are wiped on redeploy. Fine for a demo; for real durability, point
`MINIREDIS_DATA_FILE` / `MINIREDIS_AOF_FILE` at a mounted volume or swap for a hosted store.

### Configuration

All knobs are environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP listen port |
| `MINIREDIS_DATA_FILE` | `./data/miniredis.json` | JSON snapshot location |
| `MINIREDIS_AOF_FILE` | `./data/miniredis.aof` | Append-only log location |
| `MINIREDIS_AOF_ENABLED` | `true` | Toggle the AOF on/off |
| `MINIREDIS_AOF_FSYNC` | `everysec` | `always` = fsync every command; `everysec` = OS decides |
| `MINIREDIS_EXPIRY_SWEEP_MS` | `1000` | Active-expiry sweep interval |
| `QUARKUS_SHUTDOWN_TIMEOUT` | `5s` | Drain window for in-flight requests |

## Architecture in one diagram

```
Browser ──HTTP/JSON──▶ CmdResource ──▶ CommandDispatcher ──▶ Store ──▶ SnapshotCodec ──▶ data/*.json
                                          (dispatch table)      (single worker thread)      AofLog ──▶ data/*.aof
                                                                       │
                                                               ExpiryTicker (active sweep)
```

**Reads** go straight to the in-memory map. **Writes** mutate the map *and* append to the AOF
on the same single worker thread, so the on-disk command log stays in exact command order.
On shutdown the snapshot is written and the (now-redundant) AOF is cleared; on crash the stale
snapshot is rebuilt by replaying the AOF tail.
