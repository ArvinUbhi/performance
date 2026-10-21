package io.quantlab.storage;

import io.quantlab.model.ProcessKey;
import io.quantlab.model.Sample;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/** Append-only daily text segments + bounded in-memory rings; no DB, bounded writes and retention. */
public final class FileHistoryStore implements HistoryStore {
    private final Path root;
    private final int ringCapacity, retentionDays;
    private final long maxBytes;
    private final Map<ProcessKey,Deque<Sample>> rings=new ConcurrentHashMap<ProcessKey,Deque<Sample>>();
    private final BlockingQueue<Sample> queue=new ArrayBlockingQueue<Sample>(4096);
    private final AtomicLong dropped=new AtomicLong();
    private final Thread writerThread;
    private static final long MIN_SEGMENT_SIZE=1024;
    private volatile boolean closing=false;
    private static final DateTimeFormatter DAY=DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    public FileHistoryStore(Path root,int ringCapacity,int retentionDays,long maxBytes) throws IOException {
        this.root=root;this.ringCapacity=ringCapacity;this.retentionDays=retentionDays;this.maxBytes=maxBytes;
        Files.createDirectories(root);
        writerThread=new Thread(this::writeLoop,"quantlab-history-writer");writerThread.setDaemon(true);writerThread.start();
    }
    public void append(Sample sample){
        Deque<Sample> ring=rings.computeIfAbsent(sample.key,k->new ArrayDeque<Sample>());
        synchronized(ring){ring.addLast(sample);while(ring.size()>ringCapacity)ring.removeFirst();}
        if(!queue.offer(sample))dropped.incrementAndGet();
    }
    private void writeLoop(){
        String day=null;BufferedWriter out=null;long written=0,lastCleanup=0,currentSize=0;
        try {
            while(!closing||!queue.isEmpty()){
                Sample item=queue.poll(1,TimeUnit.SECONDS);
                if(item!=null){
                    String d=DAY.format(Instant.ofEpochMilli(item.timestamp));
                    if(!d.equals(day)){
                        if(out!=null)out.close();
                        Path file=root.resolve("samples-"+d+".tsv");
                        currentSize=Files.exists(file)?Files.size(file):0;
                        out=Files.newBufferedWriter(file,StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                        day=d;
                    }
                    String line=item.line();
                    long bytes=line.getBytes(StandardCharsets.UTF_8).length+1;
                    if(currentSize+bytes>maxBytes){dropped.incrementAndGet();continue;}
                    out.write(line);out.newLine();written++;currentSize+=bytes;
                }
                if(out!=null && (item==null||written%100==0))out.flush();
                if(System.currentTimeMillis()-lastCleanup>900000){
                    if(out!=null)out.flush();
                    prune(day);lastCleanup=System.currentTimeMillis();
                }
            }
        } catch(InterruptedException ignored){Thread.currentThread().interrupt();}
          catch(IOException e){dropped.addAndGet(queue.size());queue.clear();System.err.println("QuantLab history writer: "+e.getMessage());}
          finally {if(out!=null)try{out.close();}catch(IOException ignored){}}
    }
    private void prune(String currentDay) throws IOException {
        long cutoffSamples=System.currentTimeMillis()-(long)retentionDays*86400000L;
        rings.entrySet().removeIf(entry->{
            Deque<Sample> ring=entry.getValue();
            synchronized(ring){return ring.isEmpty() || ring.peekLast().timestamp<cutoffSamples;}
        });
        List<Path> paths=new ArrayList<Path>();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(root,"samples-*.tsv")){
            for(Path p:stream)paths.add(p);
        }
        Collections.sort(paths);
        long cutoff=System.currentTimeMillis()-(long)retentionDays*86400000L;
        long size=0;
        for(Path p:paths)size+=Files.size(p);
        for(Path p:paths){
            if(p.getFileName().toString().equals("samples-"+currentDay+".tsv"))continue;
            boolean expired=Files.getLastModifiedTime(p).toMillis()<cutoff;
            if(expired||size>maxBytes){long n=Files.size(p);Files.deleteIfExists(p);size-=n;}
        }
    }
    public List<Sample> query(ProcessKey key,long from,long until,int maximumPoints) throws IOException {
        if(until<from||maximumPoints<1||maximumPoints>2000)throw new IllegalArgumentException("Invalid history range");
        TreeMap<Long,Sample> all=new TreeMap<Long,Sample>();
        Deque<Sample> ring=rings.get(key);
        // Hot path: when the ring covers this time window, never read historic files.
        boolean memoryCovers=false;
        if(ring!=null) synchronized(ring){
            Sample first=ring.peekFirst();
            memoryCovers=first!=null&&first.timestamp<=from;
            if(memoryCovers)for(Sample s:ring)if(s.timestamp>=from&&s.timestamp<=until)all.put(s.timestamp,s);
        }
        if(!memoryCovers)try(DirectoryStream<Path> files=Files.newDirectoryStream(root,"samples-*.tsv")){
            for(Path p:files){
                String name=p.getFileName().toString();
                String day=name.substring(8,16);
                long start;
                try{start=java.time.LocalDate.parse(day,DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();}
                catch(Exception ex){continue;}
                if(start>until||start+86400000L<from)continue;
                try(BufferedReader r=Files.newBufferedReader(p,StandardCharsets.UTF_8)){
                    String line;while((line=r.readLine())!=null){
                        try {
                            Sample s=Sample.parse(line);
                            if(s.key.equals(key)&&s.timestamp>=from&&s.timestamp<=until)all.put(s.timestamp,s);
                        }catch(RuntimeException ignored){/* tolerate torn final line */}
                    }
                }
            }
        }
        if(!memoryCovers&&ring!=null) synchronized(ring){for(Sample s:ring)if(s.timestamp>=from&&s.timestamp<=until)all.put(s.timestamp,s);}
        List<Sample> samples=new ArrayList<Sample>(all.values());
        if(samples.size()<=maximumPoints)return samples;
        // Fixed-width time buckets. Choose the most recent observation in each bucket; do not invent averaged counters.
        List<Sample> down=new ArrayList<Sample>(maximumPoints);
        for(int i=0;i<maximumPoints;i++){
            int index=(int)Math.min(samples.size()-1,((long)(i+1)*samples.size()/maximumPoints)-1);
            down.add(samples.get(index));
        }
        return down;
    }
    public long dropped(){return dropped.get();}
    @Override public void close(){closing=true;try{writerThread.join(8000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
