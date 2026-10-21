package io.quantlab.model;

import java.util.Objects;

/** PID plus start time prevents historical data from crossing PID reuse. */
public final class ProcessKey {
    public final int pid;
    public final long startMillis;
    public ProcessKey(int pid, long startMillis) {
        if (pid < 1 || startMillis <= 0) throw new IllegalArgumentException("Invalid process identity");
        this.pid = pid;
        this.startMillis = startMillis;
    }
    @Override public boolean equals(Object o) {
        if (!(o instanceof ProcessKey)) return false;
        ProcessKey other = (ProcessKey) o;
        return pid == other.pid && startMillis == other.startMillis;
    }
    @Override public int hashCode() { return Objects.hash(pid, startMillis); }
    @Override public String toString() { return pid + "-" + startMillis; }
}
