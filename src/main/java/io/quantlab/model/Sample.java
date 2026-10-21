package io.quantlab.model;

import io.quantlab.core.Json;

/** All counters sampled together. CPU percent is one-core normalized (200% == two fully busy cores). */
public final class Sample {
    public final long timestamp;
    public final ProcessKey key;
    public final String name, state;
    public final double cpuPercent, hostCpuPercent, loadAverage;
    public final long rssBytes, readBytes, writeBytes, majorFaults, availableMemory;
    public final int threads, logicalCores;
    public Sample(long timestamp, ProcessKey key, String name, String state, double cpuPercent,
                  long rssBytes, int threads, long readBytes, long writeBytes, long majorFaults,
                  double hostCpuPercent, double loadAverage, long availableMemory, int logicalCores) {
        this.timestamp=timestamp; this.key=key; this.name=name; this.state=state; this.cpuPercent=cpuPercent;
        this.rssBytes=rssBytes; this.threads=threads; this.readBytes=readBytes; this.writeBytes=writeBytes;
        this.majorFaults=majorFaults; this.hostCpuPercent=hostCpuPercent; this.loadAverage=loadAverage;
        this.availableMemory=availableMemory; this.logicalCores=logicalCores;
    }
    public String json() {
        return "{\"timestamp\":"+timestamp+",\"pid\":"+key.pid+",\"startMillis\":"+key.startMillis+
            ",\"name\":"+Json.quote(name)+",\"state\":"+Json.quote(state)+",\"cpuPercent\":"+Json.number(cpuPercent)+
            ",\"rssBytes\":"+rssBytes+",\"threads\":"+threads+",\"readBytes\":"+readBytes+
            ",\"writeBytes\":"+writeBytes+",\"majorFaults\":"+majorFaults+
            ",\"hostCpuPercent\":"+Json.number(hostCpuPercent)+",\"loadAverage\":"+Json.number(loadAverage)+
            ",\"availableMemory\":"+availableMemory+",\"logicalCores\":"+logicalCores+"}";
    }
    /** Stable tab-separated line format: numeric fields only plus hex-encoded text. */
    public String line() {
        return timestamp+"\t"+key.pid+"\t"+key.startMillis+"\t"+Json.hex(name)+"\t"+Json.hex(state)+"\t"+
            cpuPercent+"\t"+rssBytes+"\t"+threads+"\t"+readBytes+"\t"+writeBytes+"\t"+majorFaults+"\t"+
            hostCpuPercent+"\t"+loadAverage+"\t"+availableMemory+"\t"+logicalCores;
    }
    public static Sample parse(String line) {
        String[] t=line.split("\t",-1);
        if(t.length!=15) throw new IllegalArgumentException("Expected 15 columns");
        return new Sample(Long.parseLong(t[0]),new ProcessKey(Integer.parseInt(t[1]),Long.parseLong(t[2])),
            Json.unhex(t[3]),Json.unhex(t[4]),Double.parseDouble(t[5]),Long.parseLong(t[6]),
            Integer.parseInt(t[7]),Long.parseLong(t[8]),Long.parseLong(t[9]),Long.parseLong(t[10]),
            Double.parseDouble(t[11]),Double.parseDouble(t[12]),Long.parseLong(t[13]),Integer.parseInt(t[14]));
    }
}
