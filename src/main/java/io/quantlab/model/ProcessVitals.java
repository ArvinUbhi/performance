package io.quantlab.model;

/** Immutable raw measurements. CPU counters are cumulative milliseconds, not sampled percentages. */
public final class ProcessVitals {
    public final ProcessKey key;
    public final String name;
    public final String state;
    public final long cpuMillis;
    public final long rssBytes;
    public final int threads;
    public final long readBytes;
    public final long writeBytes;
    public final long majorFaults;
    public ProcessVitals(ProcessKey key, String name, String state, long cpuMillis,
                         long rssBytes, int threads, long readBytes, long writeBytes, long majorFaults) {
        this.key=key; this.name=name; this.state=state; this.cpuMillis=cpuMillis;
        this.rssBytes=rssBytes; this.threads=threads; this.readBytes=readBytes;
        this.writeBytes=writeBytes; this.majorFaults=majorFaults;
    }
}
