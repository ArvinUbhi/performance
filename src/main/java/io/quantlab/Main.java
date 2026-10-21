package io.quantlab;

import io.quantlab.api.ApiServer;
import io.quantlab.collection.ProbeFactory;
import io.quantlab.core.SamplingEngine;
import io.quantlab.diagnostics.JcmdDiagnostics;
import io.quantlab.storage.FileHistoryStore;
import io.quantlab.storage.SnapshotStore;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Standalone launch; embed SamplingEngine and providers directly in an existing daemon if preferred. */
public final class Main {
    private Main() { }
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<String,String>();boolean allowJcmd=false;
        for(int i=0;i<args.length;i++) {
            if("--help".equals(args[i])) {usage();return;}
            if("--allow-jcmd".equals(args[i])){allowJcmd=true;continue;}
            if(!args[i].startsWith("--")||i+1>=args.length)throw new IllegalArgumentException("Expected --option value");
            options.put(args[i].substring(2),args[++i]);
        }
        int interval=number(options,"interval",10,5,300);
        int port=number(options,"port",8787,0,65535);
        int capacity=number(options,"ring",720,60,20000);
        int maxPids=number(options,"max-pids",128,1,1024);
        int budget=number(options,"cycle-budget-ms",750,20,3000);
        int retention=number(options,"retention-days",7,1,30);
        long maxBytes=number(options,"max-storage-mb",128,16,2048)*1024L*1024L;
        String host=options.getOrDefault("bind","127.0.0.1");
        String token=options.containsKey("token")?options.get("token"):System.getenv("QUANTLAB_TOKEN");
        Path root=Paths.get(options.getOrDefault("data","./quantlab-data")).toAbsolutePath();
        FileHistoryStore history=new FileHistoryStore(root.resolve("history"),capacity,retention,maxBytes);
        SnapshotStore snapshots=new SnapshotStore(root.resolve("snapshots"),200);
        SamplingEngine sampler=new SamplingEngine(ProbeFactory.processProbe(),ProbeFactory.hostProbe(),history,maxPids,budget);
        ApiServer server=new ApiServer(host,port,token,sampler,history,snapshots,new JcmdDiagnostics(allowJcmd));
        for(String s:options.getOrDefault("pids","").split(",")) {
            if(!s.trim().isEmpty())sampler.watch(Integer.parseInt(s.trim()));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            server.close();sampler.close();history.close();
        },"quantlab-shutdown"));
        sampler.start(interval);server.start();
        System.out.println("Quant Performance Lab 0.1.0: http://"+host+":"+server.port()+"/ ; collector="+sampler.collector());
        System.out.println("Sampling every "+interval+"s; watch list="+sampler.watched()+"; data="+root);
        new CountDownLatch(1).await();
    }
    private static int number(Map<String,String> o,String key,int fallback,int min,int max){
        int n=Integer.parseInt(o.getOrDefault(key,Integer.toString(fallback)));
        if(n<min||n>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);
        return n;
    }
    private static void usage(){System.out.println("Usage: ./run.sh [--pids 123,456] [--interval 10] [--port 8787] [--bind 127.0.0.1] [--data PATH] [--ring 720] [--retention-days 7] [--max-storage-mb 128] [--max-pids 128] [--cycle-budget-ms 750] [--allow-jcmd] [--token LONG_SECRET]");}
}
