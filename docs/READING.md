# miniRedis — Reading List by Topic (Java + Quarkus edition)

miniRedis is small on purpose, but each piece of it is a stand-in for a real systems topic. Below, each topic has a short explanation of what it means and why it shows up in this project, followed by the reading that explains it properly — a book chapter, a paper, or (where the topic is a mechanism rather than a theory) the primary source docs.

The Java/Quarkus-specific reading (JAX-RS, Jackson, GraalVM native-image, `@Observes`) lives at the end as Topic H.

---

## Topic A — Hash tables as the storage engine

**What it is:** miniRedis's entire database is `ConcurrentHashMap<String, Item>` — a hash table. Understanding why a hash table is the right structure (O(1) lookup, no ordering guarantees, collision handling) is the foundation for understanding every key-value store, including real Redis.

**Where it shows up:** `store/Store.java`

**Read:**
- *Introduction to Algorithms* (Cormen, Leiserson, Rivest, Stein — "CLRS"), **Chapter 11: Hash Tables**. The canonical treatment: direct addressing, chaining, open addressing, and why hash tables give amortized O(1) operations.
- *Designing Data-Intensive Applications* (Kleppmann), **Chapter 3: Storage and Retrieval**, specifically the opening section "Hash Indexes." Frames the same structure as a database engineer would — an in-memory hash map backed by an append-only log, which is close to how Redis's own `dict.c` and RDB persistence relate to each other.

---

## Topic B — Concurrent access to shared mutable state

**What it is:** Every client request touches the same map. Without synchronization, concurrent reads and writes corrupt the data structure or race. This is the topic of locks, and why Java has both `ConcurrentHashMap` (a thread-safe *implementation*) and `ReentrantReadWriteLock` (a tool for guarding a *non-thread-safe* map).

**Where it shows up:** `store/Store.java`

**Read:**
- *Java Concurrency in Practice* (Goetz et al.) — **Chapter 5: Building Blocks**, especially the section on `ConcurrentHashMap` and the "compound actions" pattern. This is the Java equivalent of OSTEP's locks chapter, but tailored to the idioms you'll actually write.
- *Modern Concurrency in Java* (A N M Bazlur Rahman — O'Reilly / Apress, ~2024). The modern companion to JCIP. Covers `ConcurrentHashMap`, structured concurrency, and the JDK 21 virtual threads / scoped values additions. Less rigorous than Goetz, more cookbook-style — read JCIP first for the theory, then this for the JDK 21 surface.
- *Operating Systems: Three Easy Pieces* (Arpaci-Dusseau & Arpaci-Dusseau, free at ostep.org), the Concurrency section — chapters **"Locks"** and **"Lock-based Concurrent Data Structures."** Explains what a lock actually buys you and where naive locking still goes wrong.
- *The Well-Grounded Java Developer* (Evans) — the chapter on the Java Memory Model (JMM). Useful once, to understand why `volatile`, `synchronized`, and `ConcurrentHashMap` exist as separate tools.

For miniRedis specifically: `ConcurrentHashMap` is the idiomatic choice. If you want to *learn* why low-level locking matters, swap to `ReentrantReadWriteLock` around a plain `HashMap` and write the same tests — you'll see the failure modes the high-level collection is hiding from you.

---

## Topic C — Key expiration: lazy vs. active

**What it is:** A TTL doesn't mean a background timer fires the instant a key expires. Real systems (including Redis) mix two strategies: check-on-read ("lazy expiry") and a periodic sweep ("active expiry"). Doing only one has a failure mode: lazy-only leaks memory on keys nobody reads again; active-only wastes CPU scanning a huge keyspace.

**Where it shows up:** `store/Store.java` (`get()` checks expiry lazily; `ExpiryTicker` sweeps every second)

**Read:**
- Redis official docs, **"How Redis expires keys"** — https://redis.io/docs/latest/develop/use/keyspace/ (the "How Redis expires keys" section). This is the primary source: real Redis uses exactly the lazy + active hybrid miniRedis imitates, and the docs explain the reasoning directly from the people who built it.

---

## Topic D — Durability through snapshotting

**What it is:** An in-memory store loses everything on restart unless you write it to disk. The two dominant strategies are snapshotting (dump the whole state periodically or on shutdown) and write-ahead logging (append every mutation, replay on restart). miniRedis takes the simpler of the two: a full JSON snapshot on shutdown, written atomically so a crash mid-write never corrupts the file.

**Where it shows up:** `store/SnapshotCodec.java` + `lifecycle/Lifecycle.java` (write-to-tmp + `Files.move(..., ATOMIC_MOVE)`)

**Read:**
- *Operating Systems: Three Easy Pieces*, **"Crash Consistency: FSCK and Journaling."** Explains exactly why "write to a temp file then rename" is a real technique and not a hack — `rename()` (and Java's `Files.move(..., ATOMIC_MOVE)`) is atomic at the filesystem level, which is what makes the tmp-file trick crash-safe.
- Redis official docs, **"Redis persistence"** — https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/. Covers RDB (snapshotting, what miniRedis does) vs. AOF (write-ahead logging, what miniRedis does *not* do) and the tradeoff between them — durability window vs. simplicity.

---

## Topic E — Client-server communication: RPC-style vs. REST

**What it is:** miniRedis exposes one endpoint, `POST /cmd`, that accepts a command name and arguments — this is RPC-style (you're calling a named operation), not REST (which models resources with nouns and lets HTTP verbs do the work). Knowing the distinction explains why real Redis's own protocol (RESP) is also RPC-style, and why "just use REST" isn't always the right shape for a command-oriented system.

**Where it shows up:** `web/CmdResource.java` (`@Path("/cmd")`, not `/keys/{id}`)

**Read:**
- Roy Fielding's PhD dissertation, *Architectural Styles and the Design of Network-based Software Architectures*, **Chapter 5: Representational State Transfer (REST)** — https://ics.uci.edu/~fielding/pubs/dissertation/top.htm. This is the actual REST paper. Reading the real thing (rather than a blog post's version of it) makes clear that REST is a set of constraints for a specific kind of system — and that a command dispatcher like miniRedis's isn't violating some rule by not being RESTful, it's just solving a different problem.

---

## Topic F — Process lifecycle and graceful shutdown

**What it is:** A server that just dies on SIGTERM can lose in-flight requests and skip its own cleanup (here: the snapshot). Graceful shutdown means catching the signal, stopping new work, letting existing work finish, then cleaning up — in that order.

**Where it shows up:** `lifecycle/Lifecycle.java` (`@Observes StartupEvent`, `@Observes ShutdownEvent`)

**Read:**
- `man 7 signal` (run `man 7 signal` on macOS/Linux, or https://man7.org/linux/man-pages/man7/signal.7.html). The primary source for what SIGTERM/SIGINT actually are and how a process is expected to respond. There's no better textbook chapter than the source document the OS itself follows.
- Quarkus application lifecycle guide — https://quarkus.io/guides/lifecycle. Specifically the section on `@Observes StartupEvent` and `ShutdownEvent`, and `quarkus.shutdown.timeout`. The Quarkus team wrote this page, so it carries the same weight as primary source — it's the framework's own contract with you about ordering.

---

## Topic G — Redis itself, as the thing being imitated

**What it is:** Once the mechanics above make sense, it's worth reading about the real system this project is a toy version of — to see which simplifications miniRedis makes and why.

**Read:**
- Redis official docs, **"Redis Introduction"** — https://redis.io/docs/latest/develop/ — the conceptual overview: what Redis actually is, and why it calls itself a "data structure server" rather than just a key-value store.
- *Redis in Action* by Josiah Carlson (free at https://redislabs.com/ebook/redis-in-action/) — practical patterns; useful for seeing the commands miniRedis reimplements (SET, GET, EXPIRE, INCR) in real usage.

---

## Topic H — The Quarkus + GraalVM-native stack

This is the Java-specific reason to pick this project over a Spring Boot version. Four sub-topics, each with the primary source:

### H.1 — JAX-RS as the HTTP layer

**What it is:** JAX-RS is the standard Java API for HTTP resource endpoints. Annotations like `@Path("/cmd")`, `@POST`, `@GET`, `@Produces` turn a class into a router. Quarkus implements JAX-RS via RESTEasy Reactive (now `quarkus-rest`).

**Read:**
- Quarkus REST guide — https://quarkus.io/guides/rest. Read the "Creating your first REST endpoint" and "Using JSON" sections. That's all you need.
- JAX-RS spec (JSR 370) — https://jcp.org/en/jsr/detail?id=370. Skim the table of contents; you don't need to read the spec itself, just know it exists and that Quarkus implements it. The Quarkus guide above is the practical version.

### H.2 — CDI (Contexts and Dependency Injection)

**What it is:** Quarkus uses CDI for `Store`, `CommandDispatcher`, and any other bean you write. `@Inject`, `@ApplicationScoped`, `@PostConstruct`. It's the Java-standard alternative to Spring's `@Autowired`.

**Read:**
- Quarkus CDI guide — https://quarkus.io/guides/cdi. Specifically the section on scopes (`@ApplicationScoped` for the singleton store/dispatcher) and constructor injection. You don't need more than this.

### H.3 — Jackson with Java records

**What it is:** Quarkus' default JSON library is Jackson. Records (`public record Item(...)`) serialize to JSON without annotations because Jackson has first-class record support. This is one of the wins of the modern Java stack over older codebases where you'd write `getXxx()/setXxx()` by hand.

**Read:**
- Quarkus Jackson guide — https://quarkus.io/guides/rest#json. The "JSON" section of the REST guide is enough. If you want depth, *Java Records* (https://docs.oracle.com/en/java/javase/21/language/records.html) is the language tutorial.

### H.4 — GraalVM native-image

**What it is:** GraalVM native-image compiles Java bytecode ahead-of-time into a standalone executable. No JVM at runtime. Result: ~50ms cold start, ~15MB RSS, single static binary. The cost: build is slower, and GraalVM needs to know about every class that would normally be loaded reflectively at runtime. For miniRedis (no reflection, no classpath scanning, no dynamic proxies) this is free.

**Read:**
- Quarkus native-image guide — https://quarkus.io/guides/building-native-image. The whole guide is ~20 minutes. Sections to focus on: "Building a native executable," "Configure the GraalVM compiler," and the list of "native-friendly" patterns. The sections you can skip for this project: native testing, native packaging in containers (you'll do that via the Dockerfile).
- GraalVM native-image reference — https://www.graalvm.org/22.0/reference-manual/native-image/. Reference doc, skim for awareness. You'll only come back here if a build fails.
- *The Definitive Guide to GraalVM Native Image* (Lubin, Seignoeur) — *optional*. Deep dive, only if you want to understand AOT compilation theory. Skip for this project.

---

## If you only have an hour

Read, in order:
1. DDIA Ch. 3, "Hash Indexes" section (15 min) — Topic A
2. *Java Concurrency in Practice* Ch. 5, "ConcurrentHashMap" section (15 min) — Topic B
3. Redis docs "How Redis expires keys" (10 min) — Topic C
4. Quarkus REST guide, "Creating your first REST endpoint" + "Using JSON" (15 min) — Topic H.1 + H.3

That covers the four ideas that actually make miniRedis work: hash-table storage, concurrency safety, expiration semantics, and the HTTP/JSON surface. Topics D, E, F, G, and the rest of H can wait until after you've written code.

After the hour: build the native binary once (`./mvnw package -Dnative`) and run it. The cold-start difference vs. JVM mode is the concrete proof that the Quarkus-native stack earns its complexity vs. Spring Boot. Without that comparison, "Quarkus vs Spring" is abstract — with it, you've learned something specific.