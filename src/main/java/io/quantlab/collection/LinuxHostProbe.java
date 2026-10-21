package io.quantlab.collection;

import io.quantlab.model.HostVitals;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class LinuxHostProbe implements HostProbe {
    private long lastTotal=-1,lastIdle=-1;
    public synchronized HostVitals read() throws Exception {
        String line=Files.readAllLines(Paths.get("/proc/stat"),StandardCharsets.US_ASCII).get(0);
        String[] t=line.trim().split("\\s+");
        long total=0;
        for(int i=1;i<t.length;i++) total+=Long.parseLong(t[i]);
        long idle=Long.parseLong(t[4])+(t.length>5?Long.parseLong(t[5]):0);
        double cpu=Double.NaN;
        if(lastTotal>=0 && total>lastTotal) cpu=100.0*(1.0-(double)(idle-lastIdle)/(total-lastTotal));
        lastTotal=total;lastIdle=idle;
        double load=Double.parseDouble(new String(Files.readAllBytes(Paths.get("/proc/loadavg")),StandardCharsets.US_ASCII).split("\\s+")[0]);
        long available=-1;
        for(String s:Files.readAllLines(Paths.get("/proc/meminfo"),StandardCharsets.US_ASCII)) {
            if(s.startsWith("MemAvailable:")) {available=Long.parseLong(s.substring(13).trim().split("\\s+")[0])*1024;break;}
        }
        return new HostVitals(cpu,load,available,Runtime.getRuntime().availableProcessors());
    }
    public String implementation(){return "linux-proc";}
}
