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
