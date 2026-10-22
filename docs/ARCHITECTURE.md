# Architecture and integration design

## Intent

Add low-impact process performance intelligence to an existing distributed Java 8 gateway/coordinator/daemon product. Avoid creating a competing process manager, database or control plane. Preserve host-local data and keep collection off the process-management control path. The included embedded HTTP server is a **demonstration integration**; the real gateway can expose the same read-only interfaces.

```text
  Gateway UI ── auth / visibility rules ── Coordinator (requests only)
                                               │
                                      Daemon per Unix host
                                               │
                    ┌──────────────────────────┴─────────────────────────┐
               Sampler (one thread)                               Snapshot store
                    │                                            named, atomic files
             ProcessProbe strategy
           OSHI or Linux /proc fallback
                    │
             ProcessVitals + HostVitals
                    │
             Immutable Sample
                    │
             bounded memory ring ── async queue ── daily files
                    │
             HistoryStore interface
```

## Strategic design patterns

1. **Strategy:** `ProcessProbe` and `HostProbe`. A host-specific collector can be swapped without changing orchestration, storage or the API.
2. **Factory:** `ProbeFactory` selects OSHI where present and Linux `/proc` where unavailable. Unsupported hosts should report missing capabilities instead of silently inventing values.
3. **Repository:** `HistoryStore` / `FileHistoryStore` hides retention and resolution choices. A daemon integration can later replace this with read-only object storage archiving if needed.
4. **Immutable snapshots:** `Sample` is a value object. Historic process identity is `(pid,startMillis)` to prevent mixing PID reuse across execution instances.
5. **Command/query separation:** Sampling and snapshot queries are read-only; registration and explicit diagnostics are separate, controlled operations. Neither modifies the managed Unix process.
6. **Resource isolation:** One shared scheduled sampler, bounded queue and fixed-size API thread pool avoid unbounded per-process threads. JSON and snapshots are generated on demand.

## Operational budget (configurable starting point; not a benchmark)

- Default polling interval: 10 seconds; minimum: 5 seconds. A cycle work budget defaults to 750 ms; processes exceeding the budget are deferred with round-robin fairness and skip counters.
- Max tracked processes: 128 per daemon, with a configured upper limit of 1,024.
- Memory history: 720 samples per execution identity (approximately two hours at 10 seconds), **not** unlimited in-memory history.
- Disk writer: one background thread, 4,096 pending samples before counting drops, 128 MiB rolling historical file limit, 7-day retention.
- Snapshots: cap 200 per instance, atomic rename after full write, no heap dumps.
- HTTP history queries: memory-ring fast path when the requested window fits; max 31-day range and max 2,000 returned points; multi-day queries may still scan daily segments, so protect multi-day queries under high volume.
- Diagnostics: disabled by default; `jcmd` timeout 8 seconds, output limited to 64 KiB. It is not invoked in the sampling loop.

Test actual CPU overhead, allocations, file I/O and tail latency at 1/10/100/500 PIDs **before** enabling fleet-wide. JNA-backed OSHI calls, especially process enumeration on some Unix versions, can be materially more expensive than direct kernel reads. Do not assume an OSHI getter is constant time on all platforms.

## Metrics and semantics

- CPU percentage uses the difference between cumulative OS CPU milliseconds and elapsed wall time. It is normal for a multithreaded process to exceed 100%.
- First CPU reading is unavailable rather than guessed.
- RSS includes shared resident pages and should not be added across processes as if it were private memory.
- `/proc/<pid>/io` I/O counts are cumulative and may be unavailable for a different user. Graphs show counters; a future addition could derive per-second rates from pairs of samples with the same execution identity.
- System load is **not** a percentage. Compare with logical CPUs cautiously; high load can include uninterruptible I/O waits.
- No process CPU performance meaning should be inferred from simple utilisation alone. The diagnostic rules provide observations, not causality.

## Integration seams

The standalone app's `Main` wires:

```java
HistoryStore history = new FileHistoryStore(root, 720, 7, 128L * 1024 * 1024);
SamplingEngine sampling = new SamplingEngine(
    ProbeFactory.processProbe(), ProbeFactory.hostProbe(), history, 128);
sampling.watch(pidProvidedByExistingDaemon);
sampling.start(10);
```

The existing daemon should provide process identity and ownership from its own process registry, not accept arbitrary caller-provided PIDs without policy checks. Let the coordinator forward **read-only queries** to the local daemon; avoid pushing all samples across the network continuously. Keep history on each host, provide capability flags per daemon, and use the gateway as an authenticated viewer and orchestrator of explicitly approved diagnostic captures.

No dependency on secrets management or process startup control is required. The new interfaces can be hooked into existing daemon lifecycle events to automatically watch/unwatch a process, preserve pre-exit samples and add change-event markers to timelines.

## Follow-on features

- Downsampled multi-resolution file segments to make 7–30-day queries cheap.
- Disk-read/write rates, cgroup effective quotas, pressure-stall indicators, NUMA and CPU affinity where supported.
- Per-thread CPU sampling on Linux and controlled correlating JVM thread dumps.
- Adaptive sampling budgets based on daemon cycle cost and host load, with skipped-sample accounting.
- JFR capture for supported Java 8 distributions, with explicit binary/runtime capability detection, approvals and output size limits.
- Lifecycle-driven snapshots and comparisons keyed by deployed process version and environment manifest.

These are **not** implemented in version 0.1.0.

## Thread-safe asynchronous snapshots (Java 8)

`SnapshotService` is an **opt-in library component** independent from `SamplingEngine`. It
owns a dedicated `ThreadPoolExecutor` with 2 workers and an `ArrayBlockingQueue` of 32
jobs by default. The pool is lazy (no workers before the first request), has named daemon
threads (`quantlab-snapshot-*`), and uses **AbortPolicy**, never CallerRuns: a saturated
snapshot queue returns a rejected request instead of blocking a daemon's control path.
Its completion/status registry is bounded (128 records by default), and completed jobs are
pruned after expiry or when capacity needs recycling. Workers have separate `ThreadLocal`
OSHI/proc probe instances so mutable CPU tick caches cannot be shared with the periodic sampler.

```java
SnapshotStore store = new SnapshotStore(root.resolve("snapshots"), 200);
SnapshotService service = new SnapshotService(
    ProbeFactory::processProbe, ProbeFactory::hostProbe, store);

// Immediately returns a job ticket, without reading /proc or touching disk.
SnapshotService.Job job = service.submit(pid, "Before deploy");
// Or consume as CompletionStage (use async continuations for lengthy processing).
CompletionStage<Snapshot> stage = service.captureAsync(pid, "Baseline");

// Upon daemon shutdown, explicitly release workers.
service.close();
```

The worker reads fresh process/host data, then serialises an immutable sample and writes
an atomic snapshot file. Since a single instantaneous read cannot establish process CPU
utilisation, `cpuPercent` and `hostCpuPercent` are **unavailable/null** in *fresh snapshots*.
Use periodic history or a future fixed-window measurement API for rates. If the process
exits between request and execution, the job fails cleanly instead of saving a stale PID.

The snapshot store serialises `create`, `get`, `list` and pruning. Its critical section is
limited to snapshot file operations; it does **not** lock the sampler, process registry or
coordinator. A bounded HTTP worker pool handles requests, but POST never waits for the
snapshot worker; the HTTP adapter returns a `202` job ticket, and clients poll status.

- `POST /api/snapshots?pid=123&label=baseline` → HTTP 202, `jobId` and `QUEUED`/`RUNNING`.
- `GET /api/snapshot-jobs?id=UUID` → job status and snapshot after completion.
- `GET /api/snapshots?pid=123` → completed saved snapshots.
- Queue saturation → HTTP 429, do **not** retry in a tight loop.
- `GET /api/status` exposes `snapshotQueueDepth`, `snapshotActiveWorkers`, `snapshotRejected`.

Thread-safety invariants verified in `SnapshotConcurrencySelfTest` and
`SnapshotServiceTest`: asynchronous admission, bounded concurrency, no caller-runs,
rejection under pressure, atomic file writes under concurrent load, and completion of
pending futures on shutdown. The sampler synchronises watch/unwatch with sample
publication to prevent a concurrent unwatch from being reversed by an in-flight read.

**Still required before production rollout:** validate OSHI/JNA behaviour and overhead on
actual Unix host types and run sustained load testing (including permission failures,
process exit races, and slow disks). `SnapshotService` is a library boundary, not an
independent service; the sample HTTP UI can be omitted when embedding it.
