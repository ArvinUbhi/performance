# Quant Performance Lab

A **Java 8-compatible, database-free, low-impact process performance lab** for Unix/Linux process managers. A standalone local web UI is included; the same Java components can be embedded behind an existing daemon/coordinator/gateway.

![Java 8](https://img.shields.io/badge/Java-8%2B-3188bd) ![OSHI](https://img.shields.io/badge/Host%20Metrics-OSHI-53b5a5) ![Database](https://img.shields.io/badge/Database-None-53b5a5)

**Status: prototype for Linux validation.** Its JVM-independent metrics work for native and Java processes. Maven includes OSHI 6.8.2 (`oshi-core`) for cross-platform process/host telemetry; a dependency-free Linux `/proc` strategy enables a JDK-only fallback. The app has not been exercised against an actual production process-management deployment.

## Quick start

**Requires:** JDK 8+ in PATH. Maven is optional, but recommended for OSHI mode.

```sh
./run.sh --pids 12345,12346
```

Open **http://127.0.0.1:8787**. Replace the example PIDs with real process IDs or add PIDs in the UI. With Maven installed, `run.sh` packages the full OSHI-enabled fat JAR; without Maven, it compiles the Linux-only fallback using `javac`. No Node.js, database, front-end build tool or separate HTTP server is needed.

```sh
# Full OSHI build (recommended)
mvn test package
java -jar target/quant-performance-lab-0.1.0.jar --pids 12345

# Read-only instance with a different directory and a slower sampler
./run.sh --interval 30 --data /var/tmp/quantlab --port 8787

# Unit/integration test suite; works with a JDK even without Maven
./test.sh
```

### Capabilities

- **Lightweight shared sampler** — CPU time deltas (100% = one fully occupied core), RSS, OS thread count, read/write byte counters, major faults, host CPU and system load; no subprocesses on the normal OSHI sampling path.
- **Live interactive UI** — dark responsive UI, multiple watched PIDs, selected-process CPU/memory/threads/I/O-throughput/host charts, 1h/24h/7d windows and JSON exports.
- **Historical data** — per-process bounded memory rings and asynchronous append-only local daily files. Keeps historical measurements after restart; time-range queries downsample to a bounded number of results.
- **Dedicated asynchronous snapshot pool** — fresh on-demand capture + atomic persistence run on **two isolated workers** (bounded queue of 32). HTTP and daemon control threads never execute snapshot collection; overload rejects immediately (HTTP 429). Queue depth, running workers and rejections are observable.
- **Durable quick snapshots** — named baseline snapshots written atomically, metadata in files, comparison of RSS, threads and host load. Instant CPU% is explicitly unavailable (requires a measurement interval). No secrets or heap data are stored.
- **Transparent diagnostic signals** — deterministic heuristics for host contention, multi-core CPU load, memory and thread footprint; no unsupported claims about root cause.
- **Optional diagnostic commands** — `jcmd Thread.print`, `GC.heap_info`, `VM.flags` only with explicit `--allow-jcmd` and with time/output limits. Outputs are not persisted.
- **Lightweight Java integration** — interfaces and adapters separate OSHI/proc, domain model, storage, sampling, API and diagnostics.

### Configuration

| Flag | Default | Description |
| --- | --- | --- |
| `--pids 123,456` | none | Initial watched processes |
| `--interval 10` | 10 seconds | Poll period; allowed 5–300s |
| `--data PATH` | `./quantlab-data` | Host-local history and snapshot directory |
| `--port 8787` | 8787 | Embedded HTTP port (0 = ephemeral) |
| `--bind ADDR` | `127.0.0.1` | Default loopback only |
| `--ring 720` | 720 | Samples per execution identity in memory |
| `--max-pids 128` | 128 | Maximum watched PIDs (cap 1,024) |
| `--cycle-budget-ms 750` | 750 ms | Sampling work limit per cycle; round-robin fairness and skip counting |
| `--retention-days 7` | 7 | Historical file retention, 1–30 days |
| `--max-storage-mb 128` | 128 MiB | Total historical file budget (16–2,048 MiB) |
| `--allow-jcmd` | disabled | Enable explicit on-demand JVM diagnostics |
| `--token SECRET` | none | Bearer token (required for non-loopback binds) |

**Safety:** Non-loopback HTTP endpoints must be placed behind HTTPS and a trusted reverse proxy. The built-in UI is designed for loopback/SSH-tunnel use and does not include a token-login form. Store bearer tokens in a secure deployment secret store, not source control or shell history. Avoid collecting sensitive JVM thread dumps without appropriate approval. Detailed limitations in [SECURITY.md](SECURITY.md).

### API examples

```sh
curl -s http://127.0.0.1:8787/api/status
curl -s http://127.0.0.1:8787/api/processes
curl -X POST 'http://127.0.0.1:8787/api/watch?pid=12345'
curl -s 'http://127.0.0.1:8787/api/history?pid=12345&max=300'
# POST returns HTTP 202 with {"jobId":"...","status":"QUEUED",...}; it never waits on profiling or disk I/O.
curl -X POST 'http://127.0.0.1:8787/api/snapshots?pid=12345&label=baseline'
curl -s 'http://127.0.0.1:8787/api/snapshot-jobs?id=JOB_UUID'
curl -s 'http://127.0.0.1:8787/api/snapshots?pid=12345' 
curl -s 'http://127.0.0.1:8787/api/compare?a=SNAPSHOT_ID&b=SNAPSHOT_ID'
# Explicit opt-in: ./run.sh --allow-jcmd ...
curl -X POST 'http://127.0.0.1:8787/api/diagnostics?pid=12345&kind=threads'
```

After a process exits, request its historical identity using `startMillis` from an old snapshot or known record; when a PID has been reused, never mix execution identities. History always records each execution's start timestamp.

### Embedding as a thread-safe Java 8 library

The recommended entry point is `SnapshotService`, not the demo HTTP server:

```java
SnapshotStore storage = new SnapshotStore(dataRoot.resolve("snapshots"), 200);
SnapshotService service = new SnapshotService(
    ProbeFactory::processProbe, ProbeFactory::hostProbe, storage);
SnapshotService.Job job = service.submit(pid, "Baseline");  // returns immediately
CompletionStage<Snapshot> result = job.completion();          // no blocking
// service.close() during daemon shutdown; pending work completes exceptionally
```

No automatic sampling or snapshot workers start when the library is loaded. A worker is
created lazily when capture is submitted. Thread-local probes isolate mutable OSHI CPU
caches from the periodic sampler; the snapshot store serialises its own file operations.
Snapshot collection never uses CallerRuns or an unbounded queue. Expensive client
continuations should use `thenApplyAsync` with their own executor rather than occupying
snapshot worker threads. Periodic sampling is a separate, optional component.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for details.

### Production integration

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the implementation layers, integration seams, and recommended limits before enabling collection on multiple Unix hosts.

### Verify compatibility

- Code targets Java 8 and is validated using `javac --release 8` (where available).
- Maven's `oshi-core` artifact is required to compile the OSHI adapter and has its own JNA and transitive dependencies.
- The fallback Linux `/proc` collector is **not** a substitute for native Solaris/AIX/FreeBSD instrumentation. For these systems use the packaged OSHI module and validate which fields OSHI supports.
- Every metric may be unavailable due to OS permissions, missing kernel support, container restrictions or differences between Java 8 distributions.
