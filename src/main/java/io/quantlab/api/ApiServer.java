package io.quantlab.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quantlab.core.Json;
import io.quantlab.core.SamplingEngine;
import io.quantlab.diagnostics.JcmdDiagnostics;
import io.quantlab.model.ProcessKey;
import io.quantlab.model.Sample;
import io.quantlab.model.Snapshot;
import io.quantlab.storage.HistoryStore;
import io.quantlab.storage.SnapshotStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/** Embedded JDK HTTP server; no web framework, static assets loaded from classpath. */
public final class ApiServer implements AutoCloseable {
    private final HttpServer server;
    private final SamplingEngine sampler;
    private final HistoryStore history;
    private final SnapshotStore snapshots;
    private final JcmdDiagnostics diagnostics;
    private final String token;
    private final long started=System.currentTimeMillis();
    public ApiServer(String host,int port,String token,SamplingEngine sampler,HistoryStore history,
                     SnapshotStore snapshots,JcmdDiagnostics diagnostics) throws IOException {
        InetAddress address=InetAddress.getByName(host);
        if(!address.isLoopbackAddress()&&(token==null||token.length()<24))
            throw new IllegalArgumentException("Non-loopback binding requires --token with at least 24 characters; deploy behind HTTPS");
        this.token=token;this.sampler=sampler;this.history=history;
        this.snapshots=snapshots;this.diagnostics=diagnostics;
        server=HttpServer.create(new InetSocketAddress(address,port),32);
        server.setExecutor(Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"quantlab-http");t.setDaemon(true);return t;}));
        server.createContext("/",this::handle);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}
    @Override public void close(){server.stop(1);}
    private void handle(HttpExchange e) throws IOException {
        try {
            e.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            e.getResponseHeaders().set("Cache-Control","no-store");
            e.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            if(token!=null && !constantEquals("Bearer "+token,e.getRequestHeaders().getFirst("Authorization"))){send(e,401,"{\"error\":\"unauthorized\"}");return;}
            String path=e.getRequestURI().getPath();String method=e.getRequestMethod();
            if("HEAD".equals(method)) {e.sendResponseHeaders(200,-1);return;}
            if("/".equals(path)){asset(e,"/web/index.html","text/html; charset=utf-8");return;}
            if("/app.js".equals(path)){asset(e,"/web/app.js","application/javascript; charset=utf-8");return;}
            if("/app.css".equals(path)){asset(e,"/web/app.css","text/css; charset=utf-8");return;}
            if(!path.startsWith("/api/")){send(e,404,"{\"error\":\"not found\"}");return;}
            Map<String,String> q=query(e.getRequestURI().getRawQuery());
            String result;
            if("/api/status".equals(path)&&"GET".equals(method)) {
                result="{\"uptimeMillis\":"+(System.currentTimeMillis()-started)+",\"collector\":"+Json.quote(sampler.collector())+
                    ",\"cycles\":"+sampler.cycles()+",\"collectionErrors\":"+sampler.errors()+
                    ",\"droppedHistorySamples\":"+history.dropped()+
                    ",\"samplingBudgetSkips\":"+sampler.skippedDueToBudget()+
                    ",\"lastCycleMillis\":"+sampler.lastCycleMillis()+
                    ",\"allowJcmd\":"+diagnostics.enabled()+"}";
            }else if("/api/processes".equals(path)&&"GET".equals(method)) {
                StringBuilder b=new StringBuilder("{\"watched\":[");
                boolean first=true;
                for(Integer pid:sampler.watched()){
                    if(!first)b.append(',');first=false;
                    Sample s=sampler.latest(pid);b.append("{\"pid\":").append(pid).append(",\"sample\":").append(s==null?"null":s.json()).append('}');
                }
                result=b.append("]}").toString();
            }else if("/api/watch".equals(path)&&"POST".equals(method)) {
                int pid=integer(q,"pid",0,1,Integer.MAX_VALUE);
                if(!sampler.watch(pid))throw new IllegalArgumentException("Watch limit reached or PID invalid");
                result="{\"watched\":"+pid+"}";
            }else if("/api/watch".equals(path)&&"DELETE".equals(method)) {
                int pid=integer(q,"pid",0,1,Integer.MAX_VALUE);
                result="{\"removed\":"+sampler.unwatch(pid)+"}";
            }else if("/api/history".equals(path)&&"GET".equals(method)) {
                int pid=integer(q,"pid",0,1,Integer.MAX_VALUE);
                Sample latest=sampler.latest(pid);
                long identity=longNumber(q,"startMillis",latest==null?0:latest.key.startMillis,1,Long.MAX_VALUE);
                long to=longNumber(q,"to",System.currentTimeMillis(),0,Long.MAX_VALUE);
                long from=longNumber(q,"from",to-3600000,0,Long.MAX_VALUE);
                if(to<from||to-from>31L*86400000L)throw new IllegalArgumentException("History window must be <=31 days");
                int max=integer(q,"max",500,1,2000);
                List<Sample> samples=history.query(new ProcessKey(pid,identity),from,to,max);
                StringBuilder b=new StringBuilder("{\"samples\":[");
                for(int i=0;i<samples.size();i++){if(i>0)b.append(',');b.append(samples.get(i).json());}
                result=b.append("]}").toString();
            }else if("/api/snapshots".equals(path)&&"POST".equals(method)) {
                int pid=integer(q,"pid",0,1,Integer.MAX_VALUE);
                Snapshot s=snapshots.create(q.get("label"),sampler.latest(pid));result=s.json();
            }else if("/api/snapshots".equals(path)&&"GET".equals(method)) {
                int pid=integer(q,"pid",0,0,Integer.MAX_VALUE);
                List<Snapshot> all=snapshots.list(pid);
                StringBuilder b=new StringBuilder("{\"snapshots\":[");
                for(int i=0;i<all.size();i++){if(i>0)b.append(',');b.append(all.get(i).json());}
                result=b.append("]}").toString();
            }else if("/api/compare".equals(path)&&"GET".equals(method)) {
                result=SnapshotStore.compare(snapshots.get(q.get("a")),snapshots.get(q.get("b")));
            }else if("/api/diagnostics".equals(path)&&"POST".equals(method)) {
                int pid=integer(q,"pid",0,1,Integer.MAX_VALUE);
                result="{\"output\":"+Json.quote(diagnostics.run(pid,q.get("kind")))+"}";
            }else {send(e,404,"{\"error\":\"not found\"}");return;}
            send(e,200,result);
        }catch(SecurityException ex){send(e,403,"{\"error\":"+Json.quote(ex.getMessage())+"}");}
         catch(IllegalArgumentException ex){send(e,400,"{\"error\":"+Json.quote(ex.getMessage())+"}");}
         catch(Exception ex){System.err.println("QuantLab API: "+ex.getClass().getSimpleName()+": "+ex.getMessage());
            send(e,500,"{\"error\":\"Internal operation failed\"}");}
        finally{e.close();}
    }
    private void asset(HttpExchange e,String name,String mime) throws IOException{
        try(InputStream in=getClass().getResourceAsStream(name)) {
            if(in==null){send(e,404,"{\"error\":\"asset missing\"}");return;}
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;
            while((count=in.read(chunk))!=-1)bytes.write(chunk,0,count);
            byte[] b=bytes.toByteArray();e.getResponseHeaders().set("Content-Type",mime);
            e.sendResponseHeaders(200,b.length);
            try(OutputStream out=e.getResponseBody()){out.write(b);}
        }
    }
    private static boolean constantEquals(String a,String b){
        if(a==null||b==null)return false;
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String,String> query(String raw){
        Map<String,String> map=new HashMap<String,String>();
        if(raw==null||raw.isEmpty())return map;
        if(raw.length()>2048)throw new IllegalArgumentException("Query too large");
        for(String part:raw.split("&")) {
            int eq=part.indexOf('=');if(eq<0)continue;
            String k=decode(part.substring(0,eq));
            String v=decode(part.substring(eq+1));
            map.put(k,v);
        }
        return map;
    }
    private static String decode(String value) {
        try{return URLDecoder.decode(value,"UTF-8");}
        catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
    private static int integer(Map<String,String> m,String key,int fallback,int min,int max){
        String v=m.get(key);int n=v==null?fallback:Integer.parseInt(v);
        if(n<min||n>max)throw new IllegalArgumentException("Invalid "+key);return n;
    }
    private static long longNumber(Map<String,String> m,String key,long fallback,long min,long max){
        String v=m.get(key);long n=v==null?fallback:Long.parseLong(v);
        if(n<min||n>max)throw new IllegalArgumentException("Invalid "+key);return n;
    }
    private static void send(HttpExchange e,int status,String text) throws IOException{
        byte[] out=text.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        e.sendResponseHeaders(status,out.length);try(OutputStream stream=e.getResponseBody()){stream.write(out);}
    }
}
