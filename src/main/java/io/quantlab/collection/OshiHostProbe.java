package io.quantlab.collection;

import io.quantlab.model.HostVitals;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;

/** OSHI tick deltas are based on prior readings; never call delayed CPU APIs on a hot path. */
public final class OshiHostProbe implements HostProbe {
    private final SystemInfo info=new SystemInfo();
    private final CentralProcessor processor=info.getHardware().getProcessor();
    private final GlobalMemory memory=info.getHardware().getMemory();
    private long[] ticks;
    public OshiHostProbe() { ticks=processor.getSystemCpuLoadTicks(); }
    public synchronized HostVitals read() {
        long[] now=processor.getSystemCpuLoadTicks();
        double cpu=100*processor.getSystemCpuLoadBetweenTicks(ticks);
        ticks=now;
        double[] loads=processor.getSystemLoadAverage(1);
        return new HostVitals(cpu,loads.length>0?loads[0]:Double.NaN,
            memory.getAvailable(),processor.getLogicalProcessorCount());
    }
    public String implementation(){return "OSHI";}
}
