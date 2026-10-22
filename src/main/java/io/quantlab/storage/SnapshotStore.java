package io.quantlab.storage;

import io.quantlab.model.Sample;
import io.quantlab.model.Snapshot;
import io.quantlab.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Durable atomic on-demand snapshots. Save only sampled metrics; never heap contents or command lines. */
public final class SnapshotStore {
    private final Path root;
    private final int maxSnapshots;
    public SnapshotStore(Path root,int maxSnapshots) throws IOException{
        this.root=root;this.maxSnapshots=maxSnapshots;Files.createDirectories(root);
    }
    public synchronized Snapshot create(String label,Sample sample) throws IOException {
        if(sample==null)throw new IllegalArgumentException("No recent measurement exists for this PID");
        if(label==null||label.trim().isEmpty())label="Snapshot";
        label=label.trim();
        if(label.length()>64)throw new IllegalArgumentException("Label exceeds 64 characters");
        String id=UUID.randomUUID().toString();
        Snapshot snapshot=new Snapshot(id,label,System.currentTimeMillis(),sample);
        Path temporary=Files.createTempFile(root,".snapshot-",".tmp");
        try {
            String content="QLSNAP1\n"+snapshot.id+"\n"+Json.hex(snapshot.label)+"\n"+snapshot.capturedAt+"\n"+sample.line()+"\n";
            Files.write(temporary,content.getBytes(StandardCharsets.UTF_8));
            try{Files.move(temporary,root.resolve(id+".snap"),StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,root.resolve(id+".snap"));}
        }finally{Files.deleteIfExists(temporary);}
        prune();return snapshot;
    }
    public synchronized Snapshot get(String id) throws IOException {
        if(id==null||!id.matches("[a-fA-F0-9-]{36}"))throw new IllegalArgumentException("Invalid snapshot ID");
        Path p=root.resolve(id+".snap");
        if(!Files.isRegularFile(p))return null;
        List<String> lines=Files.readAllLines(p,StandardCharsets.UTF_8);
        if(lines.size()!=5||!"QLSNAP1".equals(lines.get(0)))throw new IOException("Corrupted snapshot");
        return new Snapshot(lines.get(1),Json.unhex(lines.get(2)),Long.parseLong(lines.get(3)),Sample.parse(lines.get(4)));
    }
    public synchronized List<Snapshot> list(int pid) throws IOException {
        List<Snapshot> all=new ArrayList<Snapshot>();
        try(DirectoryStream<Path> files=Files.newDirectoryStream(root,"*.snap")){
            for(Path p:files){
                try{
                    Snapshot s=get(p.getFileName().toString().replace(".snap",""));
                    if(s!=null&&(pid==0||s.sample.key.pid==pid))all.add(s);
                }catch(RuntimeException | IOException ignored) { /* do not hide valid files */ }
            }
        }
        all.sort((a,b)->Long.compare(b.capturedAt,a.capturedAt));return all;
    }
    private void prune() throws IOException {
        List<Snapshot> all=list(0);
        for(int i=maxSnapshots;i<all.size();i++)Files.deleteIfExists(root.resolve(all.get(i).id+".snap"));
    }
    public static String compare(Snapshot a,Snapshot b) {
        if(a==null||b==null)throw new IllegalArgumentException("Snapshot not found");
        Sample x=a.sample,y=b.sample;
        return "{\"baseline\":"+a.json()+",\"candidate\":"+b.json()+
            ",\"sameExecution\":"+x.key.equals(y.key)+
            ",\"cpuDelta\":"+Json.number(y.cpuPercent-x.cpuPercent)+
            ",\"rssDelta\":"+(y.rssBytes-x.rssBytes)+
            ",\"threadsDelta\":"+(y.threads-x.threads)+
            ",\"hostCpuDelta\":"+Json.number(y.hostCpuPercent-x.hostCpuPercent)+
            ",\"loadDelta\":"+Json.number(y.loadAverage-x.loadAverage)+"}";
    }
}
