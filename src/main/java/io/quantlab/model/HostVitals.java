package io.quantlab.model;

public final class HostVitals {
    public final double cpuPercent;
    public final double loadAverage;
    public final long availableMemory;
    public final int logicalCores;
    public HostVitals(double cpuPercent, double loadAverage, long availableMemory, int logicalCores) {
        this.cpuPercent=cpuPercent; this.loadAverage=loadAverage;
        this.availableMemory=availableMemory; this.logicalCores=logicalCores;
    }
}
