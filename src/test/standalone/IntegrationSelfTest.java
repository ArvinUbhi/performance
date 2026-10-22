import io.quantlab.collection.HostProbe;
import io.quantlab.collection.ProcessProbe;
import io.quantlab.core.Json;
import io.quantlab.core.SamplingEngine;
import io.quantlab.core.SnapshotService;
import io.quantlab.model.*;
import io.quantlab.storage.*;
import io.quantlab.api.ApiServer;
import io.quantlab.diagnostics.JcmdDiagnostics;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.net.*;

public class IntegrationSelfTest {
    private static void check(boolean expression,String message){if(!expression)throw new AssertionError(message);}
    private static String get(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(2000);c.setReadTimeout(2000);
        try(InputStream in=c.getInputStream()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1)out.write(buf,0,n);
            return new String(out.toByteArray(),"UTF-8");
        }finally{c.disconnect();}
    }
    private static String post(String url)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod("POST");
        c.setConnectTimeout(2000);c.setReadTimeout(2000);
        try(InputStream in=c.getInputStream()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1)out.write(buf,0,n);
            return new String(out.toByteArray(),"UTF-8");
        }finally{c.disconnect();}
    }
    public static void main(String[] args)throws Exception {
        check("one\n<\"".equals(Json.unhex(Json.hex("one\n<\""))),"hex round trip");
        check("\"x\\n\\\"\"".equals(Json.quote("x\n\"")),"JSON escaping");
        ProcessKey k=new ProcessKey(42,12345);
        Sample s=new Sample(100000,k,"my worker","RUNNING",140.5,512,6,4,8,3,44,1.2,900,8);
        check(s.line().equals(Sample.parse(s.line()).line()),"sample serialization");
        check(!k.equals(new ProcessKey(42,12346)),"PID reuse identity");
        Path root=Files.createTempDirectory("qlab-test");
        FileHistoryStore store=new FileHistoryStore(root.resolve("history"),4,7,16777216);
        SnapshotStore snaps=new SnapshotStore(root.resolve("snapshots"),3);
        for(int i=0;i<10;i++)store.append(new Sample(System.currentTimeMillis()+i,k,"a\t\\", "R",2.0+i,1024+i,2,1,2,3,4,5,6,8));
        Snapshot a=snaps.create("baseline",s);Snapshot b=snaps.create("candidate",new Sample(100001,k,"second","RUNNING",150,1024,9,2,4,3,70,2.2,800,8));
        check(snaps.list(42).size()==2,"snapshots list");
        check(snaps.get(a.id).label.equals("baseline"),"snapshot persistence");
        check(SnapshotStore.compare(a,b).contains("\"rssDelta\":512"),"snapshot delta");
        Thread.sleep(1300);
        check(store.query(k,0,Long.MAX_VALUE,5).size()<=5,"downsample limit");
        ProcessProbe p=new ProcessProbe(){int count=0;public String implementation(){return "fake";}
            public ProcessVitals read(int pid){count++;return new ProcessVitals(new ProcessKey(pid,20000),"fake","R",count*1000,2048,2,0,0,0);}};
        HostProbe h=new HostProbe(){public String implementation(){return "fake";}
            public HostVitals read(){return new HostVitals(50,2.0,4096,4);}};
        SamplingEngine engine=new SamplingEngine(p,h,store,1);check(engine.watch(7),"watch accepts");
        check(!engine.watch(8),"bounded watched pids");engine.start(5);
        Thread.sleep(300);
        check(engine.latest(7)!=null,"sampler captures");
        SnapshotService snapshotJobs=new SnapshotService(()->p,()->h,snaps);
        ApiServer server=new ApiServer("127.0.0.1",0,null,engine,store,snaps,snapshotJobs,new JcmdDiagnostics(false));
        server.start();String base="http://127.0.0.1:"+server.port();
        check(get(base+"/api/status").contains("\"collector\""),"status API");
        check(get(base+"/api/processes").contains("\"pid\":7"),"process API");
        check(get(base+"/").contains("Quant Performance Lab"),"web UI");
        check(get(base+"/app.js").contains("drawChart"),"frontend resource");
        String submitted=post(base+"/api/snapshots?pid=7&label=fresh");
        check(submitted.contains("\"jobId\""),"asynchronous snapshot queued");
        String jobId=submitted.split("\"jobId\":\"")[1].split("\"")[0];
        String job="";
        for(int tries=0;tries<100;tries++){
            job=get(base+"/api/snapshot-jobs?id="+jobId);
            if(job.contains("\"status\":\"SUCCEEDED\""))break;
            Thread.sleep(20);
        }
        check(job.contains("\"status\":\"SUCCEEDED\""),"snapshot completed independently");
        check(job.contains("\"cpuPercent\":null"),"instant snapshots must not invent CPU rates");
        server.close();snapshotJobs.close();engine.close();store.close();
        FileHistoryStore reopened=new FileHistoryStore(root.resolve("history"),4,7,16777216);
        check(!reopened.query(k,0,Long.MAX_VALUE,100).isEmpty(),"historical storage survives restart");reopened.close();
        System.out.println("PASS: Java 8 core, event sample serialization, identities, bounded history, snapshot IO, delta comparison, sampling, HTTP API, UI assets, historical restart");
    }
}
