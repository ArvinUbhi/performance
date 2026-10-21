package io.quantlab.collection;

import io.quantlab.model.ProcessKey;
import io.quantlab.model.ProcessVitals;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/** OSHI adapter, loaded opportunistically; not referenced by the core when OSHI is absent. */
public final class OshiProcessProbe implements ProcessProbe {
    private final OperatingSystem os = new SystemInfo().getOperatingSystem();
    public ProcessVitals read(int pid) {
        OSProcess p=os.getProcess(pid);
        if(p==null || p.getStartTime()<=0) return null;
        return new ProcessVitals(new ProcessKey(pid,p.getStartTime()),p.getName(),p.getState().name(),
            p.getUserTime()+p.getKernelTime(),p.getResidentSetSize(),p.getThreadCount(),
            p.getBytesRead(),p.getBytesWritten(),p.getMajorFaults());
    }
    public String implementation() { return "OSHI"; }
}
