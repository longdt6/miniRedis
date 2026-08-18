# miniRedis — Implementation Plan (Java + Quarkus + GraalVM)

## Context

The folder `/Users/dolong/Documents/miniRedis/` is empty. We are building a small in-memory key-value server in Java with Quarkus, compiled to a native binary via GraalVM. The goal is twofold: learn the in-memory store / expiry / snapshot mechanism that real Redis uses, **and** learn the Quarkus-native stack as a replacement for Spring Boot.

Goals locked in:
- **Java 21 + Quarkus 3.x + GraalVM native-image.** Single native binary, fast cold start, low memory — the reason to pick this stack over Spring.
- **HTTP/JSON only.** One `POST /cmd` endpoint, no RESP, no TCP listener.
- **Simple web UI** served from the same process — static HTML+JS that calls `POST /cmd`.
- **JSON snapshot on shutdown** — atomic write (write-tmp + `Files.move(..., ATOMIC_MOVE)`), loaded on boot. Render's free tier has an **ephemeral filesystem** — snapshots survive process restarts but are wiped on redeploy.
- **Learning-first scope**: clear package boundaries, minimal external deps, idiomatic Java + Quarkus.

## Architecture (one sentence)

One Quarkus application serves an HTTP/JSON API + static UI on `:8080`. The browser sends `POST /cmd`; the JAX-RS handler calls a single `CommandDispatcher.execute(store, name, args)`; the store is a thread-safe in-memory map with lazy + ticker expiry. Quarkus handles signals and lifecycle; on `ShutdownEvent` we drain in-flight requests, snapshot to JSON, then exit.

## Directory Layout

```
/Users/dolong/Documents/miniRedis/
├── pom.xml                                # Maven, Quarkus 3.x, native profile
├── README.md                              # Build, run, test, deploy
├── render.yaml                            # Render Blueprint: web service + env vars
├── .gitignore                             # target/, data/
├── src/main/java/com/example/miniredis/
│   ├── store/
│   │   ├── Item.java                      # Record: value, expiresAt, hasExpiry
│   │   ├── Store.java                     # ConcurrentHashMap wrapper + expiry ticker
│   │   └── SnapshotCodec.java             # JSON encode/decode via Jackson
│   ├── cmd/
│   │   ├── CommandHandler.java            # @FunctionalInterface (List<String> -> Reply)
│   │   ├── Reply.java                     # Sealed type: Simple | Int | Bulk | Array | Error
│   │   ├── CommandDispatcher.java         # Dispatch table + execute(store, name, args)
│   │   ├── StringCommands.java            # SET, GET, DEL, EXISTS, INCR, KEYS, FLUSHALL, PING
│   │   └── ExpiryCommands.java            # EXPIRE, TTL
│   ├── web/
│   │   ├── CmdResource.java               # JAX-RS: POST /cmd
│   │   └── ReplyWriter.java               # Reply -> JSON for HTTP responses
│   ├── lifecycle/
│   │   └── SnapshotOnShutdown.java        # @ApplicationScoped, @Observes ShutdownEvent
│   └── QuarkusApp.java                    # Optional main; Quarkus generates one
├── src/main/resources/
│   ├── application.properties             # port, data file path, Jackson config
│   └── META-INF/resources/index.html      # Served at / by Quarkus static handler
└── src/test/java/com/example/miniredis/
    ├── store/StoreTest.java
    ├── cmd/CommandDispatcherTest.java
    ├── web/CmdResourceTest.java
    └── persistence/SnapshotCodecTest.java
```

**Dependencies (pom.xml):**
- `quarkus-rest` (or `quarkus-resteasy-reactive` — pick one; resteasy-reactive is the modern default)
- `quarkus-arc` (CDI — included by default)
- `quarkus-jackson` (JSON)
- `quarkus-junit5` + `rest-assured` (test only)

No Spring, no Lombok (records + sealed types cover what Lombok would), no reflection-heavy libs (GraalVM-safe by construction).

## Core Data Structures

```java
// store/Item.java — record, Jackson-friendly
public record Item(String value, Instant expiresAt, boolean hasExpiry) {}

// store/Store.java
@ApplicationScoped
public class Store {
    private final ConcurrentHashMap<String, Item> data = new ConcurrentHashMap<>();

    public Optional<String> get(String key)              // applies lazy expiry
    public void set(String key, String value, Duration ttl) // ttl == null → no expiry
    public boolean del(String key)
    public long exists(String key)
    public long ttl(String key)                          // -2 missing, -1 no exp
    public boolean setExpiry(String key, long seconds)
    public long incr(String key) throws NumberFormatException
    public List<String> keys()
    public void flushAll()

    // Persistence support
    public Map<String, Item> snapshot()
    public void load(Map<String, Item> items)            // skips already-expired

    // Lifecycle
    void onStart(@Observes StartupEvent ev)              // load snapshot, start ticker
    void onStop(@Observes ShutdownEvent ev)              // stop ticker (snapshot already done)
}

// Lifecycle wiring: a separate @ApplicationScoped bean holds the ticker
@ApplicationScoped
public class ExpiryTicker {
    private final ScheduledExecutorService scheduler = ...;
    void onStart(@Observes StartupEvent ev) { /* scheduleAtFixedRate */ }
    void onStop(@Observes ShutdownEvent ev) { /* shutdown() */ }
}
```

**Why `ConcurrentHashMap` instead of `HashMap` + `ReentrantReadWriteLock`:** idiomatic Java. Both work; `ConcurrentHashMap` is what a Java engineer would reach for first, and it's GraalVM-native-safe (no reflection, no proxies). If the learning goal is "understand why low-level locking exists," swap to `ReentrantReadWriteLock` around `HashMap` — the plan still works.

```java
// cmd/CommandHandler.java
@FunctionalInterface
public interface CommandHandler {
    Reply execute(Store store, List<String> args);
}

// cmd/Reply.java — sealed type, JSON-friendly
public sealed interface Reply {
    record Simple(String value) implements Reply {}
    record Int(long value) implements Reply {}
    record Bulk(String value, boolean isNil) implements Reply {}
    record Array(List<String> values) implements Reply {}
    record Error(String message) implements Reply {}
}
```

`Reply` as a sealed interface + records gives you pattern-matchable, exhaustive switches, and Jackson serializes records out of the box with no annotations needed (Quarkus' default Jackson config handles records natively).

## Command Dispatch (single shared entry)

```java
// cmd/CommandDispatcher.java
@ApplicationScoped
public class CommandDispatcher {
    private final Map<String, CommandHandler> dispatch = new HashMap<>();

    @PostConstruct
    void init() {
        dispatch.put("PING",     StringCommands::ping);
        dispatch.put("SET",      StringCommands::set);
        dispatch.put("GET",      StringCommands::get);
        dispatch.put("DEL",      StringCommands::del);
        dispatch.put("EXISTS",   StringCommands::exists);
        dispatch.put("INCR",     StringCommands::incr);
        dispatch.put("KEYS",     StringCommands::keys);
        dispatch.put("FLUSHALL", StringCommands::flushAll);
        dispatch.put("EXPIRE",   ExpiryCommands::expire);
        dispatch.put("TTL",      ExpiryCommands::ttl);
    }

    public Reply execute(Store store, String name, List<String> args) {
        CommandHandler h = dispatch.get(name.toUpperCase());
        if (h == null) return new Reply.Error("ERR unknown command '" + name + "'");
        try {
            return h.execute(store, args);
        } catch (NumberFormatException e) {
            return new Reply.Error("ERR value is not an integer");
        }
    }
}
```

A handler is one expression:

```java
public final class StringCommands {
    public static Reply get(Store s, List<String> args) {
        return s.get(args.get(0))
                .map(v -> (Reply) new Reply.Bulk(v, false))
                .orElse(new Reply.Bulk(null, true));
    }
    public static Reply set(Store s, List<String> args) {
        // Future: parse optional EX seconds before calling s.set
        s.set(args.get(0), args.get(1), null);
        return new Reply.Simple("OK");
    }
    // ...
}
```

## HTTP / Web UI

Endpoint: `POST /cmd`, body `{"cmd":"SET","args":["foo","bar"]}`.

```java
// web/CmdResource.java
@Path("/cmd")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CmdResource {
    @Inject Store store;
    @Inject CommandDispatcher dispatcher;

    @POST
    public ReplyDto handle(CmdRequest req) {
        Reply r = dispatcher.execute(store, req.cmd(), req.args());
        return ReplyWriter.toDto(r);
    }
    public record CmdRequest(String cmd, List<String> args) {}
}
```

`ReplyWriter` maps the sealed `Reply` to a small DTO for JSON output:

```json
{"type":"simple","value":"PONG"}
{"type":"int","value":42}
{"type":"bulk","value":"...","nil":false}
{"type":"array","value":["a","b"]}
{"type":"error","value":"ERR ..."}
```

**Static UI:** Quarkus serves files under `src/main/resources/META-INF/resources/` at `/` automatically — no extra dependency. Drop `index.html` there; it's served as-is. The same vanilla HTML+JS UI as the Go plan: `<button>`s for SET/GET/DEL/EXPIRE/TTL/INCR/PING, `fetch("/cmd", ...)`, render into a `<pre>`.

## Persistence

**File**: `$DATA_FILE` env var, default `./data/miniredis.json` (in dev) or `/tmp/miniredis.json` (on Render).

**Atomic write:**
```java
Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
Files.writeString(tmp, json, CREATE, WRITE, TRUNCATE_EXISTING);
Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
```

**Load:** on `StartupEvent`, before any listener accepts traffic (Quarkus fires `StartupEvent` before the HTTP server opens, so a partially-loaded store is never observable).

**Save:** on `ShutdownEvent`, *after* Quarkus has already drained the HTTP server:

```java
@ApplicationScoped
public class SnapshotOnShutdown {
    @Inject Store store;
    @ConfigProperty(name = "miniredis.data-file") String dataFile;

    void onStop(@Observes ShutdownEvent ev) {
        // Quarkus has already stopped accepting new HTTP requests.
        // In-flight requests have finished (ShutdownEvent fires after).
        try {
            String json = SnapshotCodec.encode(store.snapshot());
            Path file = Path.of(dataFile);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Log and continue — we're shutting down anyway
        }
    }
}
```

JSON shape (Jackson, no annotations needed because `Item` is a record):
```json
{
  "version": 1,
  "items": {
    "key1":     {"value":"...","hasExpiry":false},
    "key2":     {"value":"...","hasExpiry":true,"expiresAt":"2026-08-14T10:30:00Z"}
  }
}
```

## Lifecycle (Quarkus handles the hard parts)

Quarkus already:
- Catches SIGTERM/SIGINT (configurable: `quarkus.shutdown.timeout=5s`)
- Stops the HTTP server (drains in-flight requests up to the timeout)
- Fires `StartupEvent` *before* the HTTP server starts
- Fires `ShutdownEvent` *after* the HTTP server has stopped

So the lifecycle is just two observers:

```java
@ApplicationScoped
public class Lifecycle {
    @Inject Store store;
    @ConfigProperty(name = "miniredis.data-file") String dataFile;

    void onStart(@Observes StartupEvent ev) {
        // 1. Load snapshot if it exists
        // 2. Start expiry ticker
    }

    void onStop(@Observes ShutdownEvent ev) {
        // 1. Stop expiry ticker
        // 2. Write snapshot atomically
    }
}
```

Compare to the Go plan, where this is wired manually in `main.go` — Quarkus does it for you.

## Tests (`./mvnw test`)

- `store/StoreTest.java` — JUnit 5
  - `setGet`, `del`, `exists`, `incr`
  - `expire_lazyExpiry` (use `Clock` injected via `@ConfigProperty` or a test-only time source)
  - `expire_tickerSweep` (start ticker at 50ms, sleep 150ms, assert gone)
  - `ttl_noExpiry`, `ttl_missing`, `ttl_withExpiry`
- `cmd/CommandDispatcherTest.java`
  - `ping_noArgs`, `ping_echo`, `setGetRoundTrip`
  - `incr_notInteger` returns `Reply.Error`
- `web/CmdResourceTest.java` — `@QuarkusTest` + REST Assured
  - `postCmd_ping`, `postCmd_setThenGet`
  - `getStaticIndex` — `/` returns the embedded HTML
- `persistence/SnapshotCodecTest.java`
  - `roundTrip`, `atomicWriteNoTmpLeft`, `loadSkipsExpired`

## Run & Deploy

**Local (JVM mode, fastest iteration):**
```bash
cd /Users/dolong/Documents/miniRedis
./mvnw quarkus:dev
# Browser:
open http://localhost:8080
# Ctrl-C triggers Quarkus shutdown → @Observes ShutdownEvent → snapshot to data/miniredis.json
```

**Local (native build, the point of the stack):**
```bash
./mvnw package -Dnative
./target/miniredis-runner
open http://localhost:8080
# Notice: ~50ms cold start, ~15MB RSS
```

**Deploy to Render:**
1. Push repo to GitHub.
2. Render dashboard: **New → Web Service → connect repo**.
3. Settings:
   - **Environment**: Java
   - **Build Command**: `./mvnw package -Dnative -DskipTests` *(or use Docker — see below)*
   - **Start Command**: `./target/miniredis-runner`
   - **Instance Type**: Free
   - **Env Vars**: `PORT=8080`, `MINIREDIS_DATA_FILE=/tmp/miniredis.json`
4. ⚠ **Render's free tier does NOT have GraalVM installed.** You have two real options:
   - **Docker build**: ship a `Dockerfile` that uses `quay.io/quarkus/quarkus-micro-image:2.0` as the base and runs `./mvnw package -Dnative` inside. Render's free tier runs Docker fine.
   - **JVM mode**: build with `./mvnw package` (no `-Dnative`). Slower cold start, more memory, but trivially deployable. Still a valid learning comparison.

A small `Dockerfile` is included in the plan; it's the recommended Render path:

```dockerfile
FROM quay.io/quarkus/quarkus-micro-image:2.0
WORKDIR /app
COPY .. .
RUN ./mvnw package -Dnative
ENTRYPOINT ["./target/miniredis-runner"]
```

## Critical Files (touch points)

- `pom.xml` — Quarkus BOM, native profile, dependencies
- `src/main/java/com/example/miniredis/store/Store.java` — storage + expiry
- `src/main/java/com/example/miniredis/cmd/CommandDispatcher.java` — shared execute
- `src/main/java/com/example/miniredis/web/CmdResource.java` — HTTP layer
- `src/main/java/com/example/miniredis/lifecycle/Lifecycle.java` — startup + shutdown observers
- `src/main/resources/META-INF/resources/index.html` — UI
- `src/main/resources/application.properties` — port, data file
- `Dockerfile` — Render deploy via native image

## Verification (end-to-end)

1. `./mvnw test` — all unit + integration tests pass.
2. `./mvnw quarkus:dev` — server boots, loads any prior snapshot.
3. Open `http://localhost:8080` — UI shows buttons; click PING → result panel shows `PONG`; SET/GET through UI works.
4. `Ctrl-C` — Quarkus graceful shutdown fires; `data/miniredis.json` exists; restart → previous keys restored.
5. `./mvnw package -Dnative && ./target/miniredis-runner` — confirm fast cold start, low memory (`ps aux | grep miniredis-runner`).
6. Push to GitHub → Render builds the Docker image → public URL serves the UI.