package io.quantlab.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Opt-in only. Diagnostic outputs are never persisted and can expose sensitive information. */
public final class JcmdDiagnostics {
    private final boolean enabled;
    public JcmdDiagnostics(boolean enabled){this.enabled=enabled;}
    public boolean enabled(){return enabled;}
    public String run(int pid,String kind) throws Exception {
        if(!enabled)throw new SecurityException("jcmd diagnostics disabled; use --allow-jcmd");
        if(pid<1)throw new IllegalArgumentException("Invalid PID");
        String command;
        if("threads".equals(kind))command="Thread.print";
        else if("heap".equals(kind))command="GC.heap_info";
        else if("flags".equals(kind))command="VM.flags";
        else throw new IllegalArgumentException("Unsupported diagnostic command");
        Process p=new ProcessBuilder("jcmd",Integer.toString(pid),command).redirectErrorStream(true).start();
        ExecutorService reader=Executors.newSingleThreadExecutor(r->{
            Thread t=new Thread(r,"quantlab-jcmd-reader");t.setDaemon(true);return t;
        });
        try {
            // Read concurrently: jcmd can produce more than a pipe buffer's capacity and would deadlock
            // if we waited for termination before consuming output.
            Future<String> output=reader.submit(()->{
                ByteArrayOutputStream data=new ByteArrayOutputStream();
                byte[] buffer=new byte[4096];int n;boolean truncated=false;
                try(InputStream input=p.getInputStream()){
                    while((n=input.read(buffer))!=-1){
                        int count=Math.min(n,Math.max(0,65536-data.size()));
                        if(count>0)data.write(buffer,0,count);
                        if(count<n)truncated=true;
                    }
                }
                return new String(data.toByteArray(),StandardCharsets.UTF_8)+(truncated?"\n[output truncated at 64 KiB]":"");
            });
            if(!p.waitFor(8,TimeUnit.SECONDS))throw new IOException("Diagnostic timed out");
            String result=output.get(2,TimeUnit.SECONDS);
            if(p.exitValue()!=0)throw new IOException("jcmd failed with exit code "+p.exitValue());
            return result;
        }finally{p.destroyForcibly();reader.shutdownNow();}
    }
}
