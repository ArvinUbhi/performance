package io.quantlab.collection;

import io.quantlab.model.ProcessKey;
import io.quantlab.model.ProcessVitals;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Dependency-free Linux fallback. Linux-specific, not a universal Unix implementation. */
public final class LinuxProcessProbe implements ProcessProbe {
    private final long clockTicks=detectClockTicks();
    private final long bootMillis=detectBootMillis();
    public ProcessVitals read(int pid) throws Exception {
        if(pid<1) return null;
        Path dir=Paths.get("/proc",Integer.toString(pid));
        Path stat=dir.resolve("stat");
        if(!Files.isReadable(stat)) return null;
        String value=new String(Files.readAllBytes(stat),StandardCharsets.US_ASCII);
        int close=value.lastIndexOf(") ");
        if(close<0) throw new IOException("Malformed proc stat");
        String name=value.substring(value.indexOf('(')+1,close);
        String[] f=value.substring(close+2).trim().split("\\s+");
        if(f.length<22) throw new IOException("Truncated proc stat");
        long start=bootMillis+1000*Long.parseLong(f[19])/clockTicks;
        long cpu=1000*(Long.parseLong(f[11])+Long.parseLong(f[12]))/clockTicks;
        long major=Long.parseLong(f[9]);
        int threads=Integer.parseInt(f[17]);
        long rss=-1;
        Path status=dir.resolve("status");
        if(Files.isReadable(status)) {
            for(String s:Files.readAllLines(status,StandardCharsets.US_ASCII)) {
                if(s.startsWith("VmRSS:")) { rss=Long.parseLong(s.substring(6).trim().split("\\s+")[0])*1024; break; }
            }
        }
        long read=-1,write=-1;
        try {
            for(String line:Files.readAllLines(dir.resolve("io"),StandardCharsets.US_ASCII)) {
                if(line.startsWith("read_bytes:")) read=Long.parseLong(line.substring(11).trim());
                else if(line.startsWith("write_bytes:")) write=Long.parseLong(line.substring(12).trim());
            }
        } catch(IOException | SecurityException ignored) { /* optional I/O counters */ }
        return new ProcessVitals(new ProcessKey(pid,start),name,f[0],cpu,rss,threads,read,write,major);
    }
    public String implementation(){return "linux-proc";}
    private static long detectBootMillis(){
        try {for(String l:Files.readAllLines(Paths.get("/proc/stat"),StandardCharsets.US_ASCII))
            if(l.startsWith("btime ")) return Long.parseLong(l.substring(6).trim())*1000;}
        catch(Exception ex){ throw new IllegalStateException("Cannot identify Linux boot time",ex); }
        throw new IllegalStateException("Missing /proc/stat btime");
    }
    private static long detectClockTicks(){
        try {
            Process p=new ProcessBuilder("getconf","CLK_TCK").redirectErrorStream(true).start();
            boolean done=p.waitFor(2,TimeUnit.SECONDS);
            if(!done){p.destroyForcibly();throw new IOException("getconf timed out");}
            if(p.exitValue()==0) {
                try(BufferedReader r=new BufferedReader(new java.io.InputStreamReader(p.getInputStream(),StandardCharsets.US_ASCII))) {
                    long v=Long.parseLong(r.readLine().trim());
                    if(v>=1 && v<=100000) return v;
                }
            }
        } catch(Exception ignored) { /* Linux usually uses 100 HZ; OSHI is preferred for correctness */ }
        return 100;
    }
}
