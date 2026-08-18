# miniRedis — Components & Communication (Java + Quarkus)

Three views of the same Quarkus application. The first shows what is in it and how the parts connect. The second walks one command end-to-end. The third shows what happens on startup and on shutdown — the snapshot boundary, and where Quarkus takes work off your hands compared to the original Go plan.

> **Removed**: RESP2 wire format and the TCP listener. The browser is the only client; everything is HTTP/JSON.

---

## 1. Components & how they talk

One binary (JVM or native-image), one Quarkus application, one external port. The store is the only piece with state; everything else is a translation layer in front of `CommandDispatcher`.

```
   EXTERNAL                  LISTENER                              CORE
 ┌──────────┐   HTTP/JSON   ┌──────────────────┐
 │ Browser  │ ────────────▶ │ JAX-RS resource  │
 └──────────┘               │ POST /cmd        │
                            │ + static /       │
                            └────────┬─────────┘
                                     │ CmdRequest{cmd, args}
                                     ▼
                            ┌──────────────────┐
                            │ CommandDispatcher│  ◀── dispatch table
                            │   .execute()     │
                            └────────┬─────────┘
                                     │ Set(key, value)
                                     ▼
                            ┌──────────────────┐
                            │     Store        │  ConcurrentHashMap
                            │ + ExpiryTicker   │  + scheduled sweep
                            └────────┬─────────┘
                                     │
 ┌────────────┐   JSON dump  ┌───────┴─────────┐
 │ data/*.json│ ◀──────────▶ │ SnapshotCodec   │
 │ (disk)     │  read at boot│ (Jackson)       │
 └────────────┘              └─────────────────┘

 ↑ Quarkus fires StartupEvent before HTTP server opens
 ↑ Quarkus fires ShutdownEvent after HTTP server has drained
```

**Reading the diagram:**
- **One external actor**: the Browser. UI button clicks send `POST /cmd`.
- **One listener**: the JAX-RS resource serves both `POST /cmd` (dynamic) and `/` (static UI). Quarkus does this with no extra config — anything under `META-INF/resources/` is served at `/`.
- **Three core components**: `CmdResource` → `CommandDispatcher` → `Store`. The store is the only one with state.
- The **SnapshotCodec is a side door** into the store. It reads/writes the store directly, not through `CommandDispatcher` — there's no "SAVE" command, the snapshot is a lifecycle concern.
- **Lifecycle events** (Quarkus-managed, not in your code): `StartupEvent` fires before the HTTP server opens; `ShutdownEvent` fires after the HTTP server has drained. Your two observers do the load and the save.

---

## 2. One command, end-to-end

What happens when the UI sends `SET foo bar`:

```
  ┌─────────┐   {cmd,args}    ┌──────────────────┐
  │ Browser │ ───────────────▶│   CmdResource    │
  └─────────┘                 │ POST /cmd        │
       ▲                      └────────┬─────────┘
       │                               │ CmdRequest → dispatcher.execute
       │                               ▼
       │                       ┌──────────────────┐
       │                       │CommandDispatcher │  ◀── dispatch table
       │                       └────────┬─────────┘
       │                                │ StringCommands::set
       │                                ▼
       │                       ┌──────────────────┐
       │                       │      Store       │  data.put(k, item)
       │                       │  map[k]=Item     │
       │                       └────────┬─────────┘
       │                                │ Reply.Simple("OK")
       │                                ▼
       │                       ┌──────────────────┐
       │                       │  ReplyWriter     │  Reply → JSON DTO
       │                       └────────┬─────────┘
       │                                │ {"type":"simple","value":"OK"}
       │ JSON response                  │
       └────────────────────────        ┘
```

**Three pieces of work** between request and reply:
1. The JAX-RS resource decodes JSON into `CmdRequest`.
2. `CommandDispatcher` dispatches to the SET handler.
3. The store writes the item.

The **`ReplyWriter` is the only place** that knows about response shape — for the HTTP path it's just `{"type":"simple","value":"OK"}`.

---

## 3. Startup and shutdown — the persistence boundary

Snapshot is **loaded before the HTTP server opens** (so a partially-loaded server is never reachable) and **saved after the HTTP server drains** (so an in-flight request is never lost). Quarkus owns the ordering of events; you just observe them.

```
STARTUP (Quarkus fires StartupEvent)       SHUTDOWN (Quarkus fires ShutdownEvent)
──────────────────────────────              ──────────────────────────────
                          ┌───────────────────────────┐
   @Observes StartupEvent │                           │ @Observes ShutdownEvent
   ──▶ Store.load()       │                           │ ◀── Store.snapshot() + write
                          │        SERVING            │
   (Quarkus then opens    │                           │ (Quarkus had already stopped
    the HTTP server,      │                           │  accepting new requests;
    starts the CDI        │                           │  ShutdownEvent fires after
    container, etc.)      │                           │  in-flight requests finished)
                          └───────────────────────────┘
                                    │
                                    │ touches
                                    ▼
                          ┌───────────────────────────┐
                          │   data/miniredis.json     │
                          │  (read at boot,           │
                          │   write at exit)          │
                          └───────────────────────────┘
```

**Where Quarkus does the work for you** (that the original Go plan did manually):

| Lifecycle step                | Go plan                              | Quarkus |
| ----------------------------- | ------------------------------------ | ------- |
| Catch SIGTERM/SIGINT          | `signal.NotifyContext` in `main.go`  | Built-in |
| Stop accepting new requests   | `Listener.Shutdown(ctx)`             | Built-in |
| Wait for in-flight to finish  | Manual drain loop                    | Built-in (`quarkus.shutdown.timeout`) |
| Run user code at boot         | Direct call in `main`                | `@Observes StartupEvent` |
| Run user code at shutdown     | Manual ordering                      | `@Observes ShutdownEvent` (after drain) |

The order — "drain HTTP, then save snapshot" — is **inherent to how Quarkus fires `ShutdownEvent`**. You don't have to coordinate it.

**Two moments** when the store touches disk:
- **At boot**: `Store.load()` reads JSON, skips keys whose `hasExpiry && expiresAt < now`. Missing file → start empty, no error.
- **At exit**: `SnapshotCodec.encode()` + write tmp + `Files.move(..., ATOMIC_MOVE)`. The HTTP server has already stopped, so no request can be mid-flight.

**On Render free tier** the file lives at `/tmp/miniredis.json`. The disk is ephemeral — snapshots survive a process restart (Render spins instances down and back up), but are wiped on a redeploy. Good enough for a demo.

---

## 4. What changed from the Go plan, and why

The architecture (HTTP → dispatcher → store → snapshot) is identical. The differences are Java/Quarkus-shaped:

| Concern              | Go plan                                  | Java + Quarkus plan                                  |
| -------------------- | ---------------------------------------- | ---------------------------------------------------- |
| HTTP listener        | `net/http` + manual mux                 | JAX-RS (`@Path`, `@POST`)                            |
| Static UI            | `//go:embed static` + manual handler     | Drop in `META-INF/resources/`, Quarkus serves it     |
| Dispatch table       | `map[string]struct{H,N}`                | `Map<String, CommandHandler>` + `@PostConstruct`     |
| Handler signature    | `func(*Store, []string) (any, error)`    | `CommandHandler.execute(Store, List<String>) Reply`  |
| Reply type           | `any` + tagged JSON wrapper              | Sealed interface + records (pattern-matchable)       |
| Store concurrency    | `sync.RWMutex` around `map[string]Item`  | `ConcurrentHashMap<String, Item>` (or `RWLock` for the learning point) |
| Expiry ticker        | `time.NewTicker(1 * time.Second)`       | `ScheduledExecutorService.scheduleAtFixedRate`       |
| JSON                 | `encoding/json`                          | Jackson (`quarkus-jackson`)                          |
| Atomic file move     | `os.Rename`                              | `Files.move(..., ATOMIC_MOVE)`                       |
| Graceful shutdown    | Manual `signal.NotifyContext` + `srv.Shutdown` | `@Observes ShutdownEvent`                       |
| Tests                | `go test` + `httptest`                   | JUnit 5 + `@QuarkusTest` + REST Assured              |
| Build artifact       | Static binary, ~6MB                      | JVM jar (~30MB) **or** native binary (~15MB, ~50ms cold start) |

The native binary is the reason to pick this stack. JVM mode is fine for local development; the learning payoff comes from building native and seeing the cold-start difference.

---

## Component count at a glance

| Layer        | Files                                                              | Talks to                  |
| ------------ | ------------------------------------------------------------------ | ------------------------- |
| Entrypoint   | (none — Quarkus generates)                                         | —                         |
| Listener     | `web/CmdResource.java`                                             | `CommandDispatcher`       |
|              | `META-INF/resources/index.html` (served at `/`)                    | (HTTP layer)              |
| Commands     | `cmd/CommandDispatcher.java` (dispatch)                            | `Store`                   |
|              | `cmd/StringCommands.java`                                          | `Store`                   |
|              | `cmd/ExpiryCommands.java`                                          | `Store`                   |
| Storage      | `store/Store.java`                                                 | `SnapshotCodec`           |
|              | `store/ExpiryTicker.java` (ticker lifecycle)                       | `Store`                   |
| Persistence  | `store/SnapshotCodec.java`                                         | `Store`, disk              |
| Lifecycle    | `lifecycle/Lifecycle.java`                                         | `Store`, `SnapshotCodec`  |

**Total**: 1 application, 7 production Java classes, 1 sealed interface + 5 record subtypes, 1 static asset, 1 HTTP port (`:8080`), 1 disk file.

---

## 5. Quarkus + GraalVM — what to know before coding

Three things that will bite you if you don't know them:

**a) Records are GraalVM-native-safe.** Jackson handles them without annotations, reflection-free proxies aren't generated for them, and they're a first-class citizen in native-image. Use `record` everywhere you'd reach for a Lombok `@Value` in Spring.

**b) Quarkus does build-time DI wiring.** You cannot do `Class.forName(...)` or scan the classpath at runtime in native mode. Stick to constructor injection (`@Inject`) and `@ConfigProperty`; never reflection-based lookups of beans. This is the same idiom Quarkus pushes in JVM mode, so writing code this way from day one means your native build just works.

**c) `quarkus.shutdown.timeout` defaults to 30s.** That's plenty for miniRedis, but it's the knob to know if in-flight requests are being cut off. Set `quarkus.shutdown.timeout=5s` if you want a tighter window.