package io.quantlab.core;

import io.quantlab.collection.HostProbe;
import io.quantlab.collection.ProcessProbe;
import io.quantlab.model.HostVitals;
import io.quantlab.model.ProcessVitals;
import io.quantlab.model.Sample;
import io.quantlab.storage.HistoryStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded single sampler. Nothing on this thread blocks the process-control path. */
public final class SamplingEngine implements AutoCloseable {
    private final ProcessProbe processProbe;
    private final HostProbe hostProbe;
    private final HistoryStore history;
    private final ConcurrentSkipListSet<Integer> watched=new ConcurrentSkipListSet<Integer>();
    private final Map<Integer,TimedVitals> previous=new HashMap<Integer,TimedVitals>();
    private final ConcurrentHashMap<Integer,Sample> latest=new ConcurrentHashMap<Integer,Sample>();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"quantlab-sampler");t.setDaemon(true);return t;});
    private final int maximumPids;
    private final long cycleBudgetNanos;
    private int nextIndex;
    private final AtomicLong skippedDueToBudget=new AtomicLong(), lastCycleMillis=new AtomicLong();
    private final AtomicLong errors=new AtomicLong(),cycles=new AtomicLong();
    public SamplingEngine(ProcessProbe processProbe,HostProbe hostProbe,HistoryStore history,int maximumPids) {
        this(processProbe,hostProbe,history,maximumPids,750);
    }
    public SamplingEngine(ProcessProbe processProbe,HostProbe hostProbe,HistoryStore history,int maximumPids,long cycleBudgetMillis) {
        this.processProbe=processProbe;this.hostProbe=hostProbe;this.history=history;this.maximumPids=maximumPids;
        if(cycleBudgetMillis<20) throw new IllegalArgumentException("Sampling budget too small");
        this.cycleBudgetNanos=cycleBudgetMillis*1000000L;
    }
    public synchronized boolean watch(int pid) {
        if(pid<=0) return false;
        if(!watched.contains(pid)&&watched.size()>=maximumPids) return false;
        watched.add(pid);return true;
    }
    public boolean unwatch(int pid){latest.remove(pid);return watched.remove(pid);}
    public SortedSet<Integer> watched(){return Collections.unmodifiableSortedSet(new TreeSet<Integer>(watched));}
    public Sample latest(int pid){return latest.get(pid);}
    public List<Sample> latestAll(){
        List<Sample> list=new ArrayList<Sample>(latest.values());
        Collections.sort(list,(a,b)->Integer.compare(a.key.pid,b.key.pid));return list;
    }
    public long errors(){return errors.get();}
    public long cycles(){return cycles.get();}
    public long skippedDueToBudget(){return skippedDueToBudget.get();}
    public long lastCycleMillis(){return lastCycleMillis.get();}
    public String collector(){return processProbe.implementation()+" / "+hostProbe.implementation();}
    public void start(int intervalSeconds){scheduler.scheduleWithFixedDelay(this::tick,0,intervalSeconds,TimeUnit.SECONDS);}
    private void tick(){
        final long startNanos=System.nanoTime();
        final long time=System.currentTimeMillis();
        final HostVitals host;
        try {host=hostProbe.read();} catch(Exception ex){errors.incrementAndGet();return;}
        List<Integer> ids=new ArrayList<Integer>(watched);
        int attempted=0;
        for(int i=0;i<ids.size();i++){
            if(System.nanoTime()-startNanos>cycleBudgetNanos){
                skippedDueToBudget.addAndGet(ids.size()-i);
                break;
            }
            int pid=ids.get((nextIndex+i)%ids.size());
            attempted++;
            try {
                ProcessVitals v=processProbe.read(pid);
                if(v==null){latest.remove(pid);previous.remove(pid);continue;}
                TimedVitals old=previous.put(pid,new TimedVitals(time,v));
                double cpu=Double.NaN;
                if(old!=null&&old.v.key.equals(v.key)&&time>old.time&&v.cpuMillis>=old.v.cpuMillis)
                    cpu=100.0*(v.cpuMillis-old.v.cpuMillis)/(time-old.time);
                Sample s=new Sample(time,v.key,v.name,v.state,cpu,v.rssBytes,v.threads,v.readBytes,
                    v.writeBytes,v.majorFaults,host.cpuPercent,host.loadAverage,host.availableMemory,host.logicalCores);
                latest.put(pid,s);history.append(s);
            } catch(Exception | LinkageError ex) {errors.incrementAndGet();latest.remove(pid);previous.remove(pid);}
        }
        if(!ids.isEmpty())nextIndex=(nextIndex+attempted)%ids.size();
        lastCycleMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startNanos));
        cycles.incrementAndGet();
    }
    @Override public void close(){scheduler.shutdownNow();}
    private static final class TimedVitals {
        final long time;final ProcessVitals v;
        TimedVitals(long time,ProcessVitals v){this.time=time;this.v=v;}
    }
}
