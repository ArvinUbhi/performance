package io.quantlab.core;

import io.quantlab.collection.HostProbe;
import io.quantlab.collection.ProcessProbe;
import io.quantlab.model.HostVitals;
import io.quantlab.model.ProcessVitals;
import io.quantlab.model.Sample;
import io.quantlab.model.Snapshot;
import io.quantlab.storage.SnapshotStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * On-demand snapshot boundary for embedding in a daemon. No collection or disk I/O happens
 * on callers' threads. Only bounded, dedicated snapshot workers touch the supplied probes.
 * No threads are started until a snapshot is requested.
 */
public final class SnapshotService implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final ThreadLocal<ProcessProbe> processProbes;
    private final ThreadLocal<HostProbe> hostProbes;
    private final SnapshotStore store;
    private final Object lifecycle = new Object();
    private final LinkedHashMap<String,Job> jobs = new LinkedHashMap<String,Job>();
    private final int maxRecentJobs;
    private final AtomicLong rejected = new AtomicLong();
    private volatile boolean closed;

    public SnapshotService(Supplier<ProcessProbe> processFactory, Supplier<HostProbe> hostFactory,
                           SnapshotStore store) {
        this(processFactory,hostFactory,store,2,32,128);
    }

    public SnapshotService(Supplier<ProcessProbe> processFactory, Supplier<HostProbe> hostFactory,
                           SnapshotStore store, int workerCount, int maxQueued, int maxRecentJobs) {
        if(processFactory==null||hostFactory==null||store==null)throw new NullPointerException("Snapshot dependencies");
        if(workerCount<1||workerCount>8||maxQueued<1||maxQueued>1024||maxRecentJobs<workerCount+maxQueued)
            throw new IllegalArgumentException("Invalid snapshot pool bounds");
        this.store=store;
        this.maxRecentJobs=maxRecentJobs;
        this.processProbes=ThreadLocal.withInitial(processFactory);
        this.hostProbes=ThreadLocal.withInitial(hostFactory);
        this.workers=new ThreadPoolExecutor(workerCount,workerCount,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(maxQueued),new ThreadFactory(){
                private final AtomicLong sequence=new AtomicLong();
                @Override public Thread newThread(Runnable work){
                    Thread t=new Thread(work,"quantlab-snapshot-"+sequence.incrementAndGet());
                    t.setDaemon(true);return t;
                }
            },new ThreadPoolExecutor.AbortPolicy());
    }

    /** Admission is nonblocking; a full queue fails immediately, never CallerRuns. */
    public Job submit(int pid,String label) {
        if(pid<1)throw new IllegalArgumentException("Invalid PID");
        // Validate before queuing. SnapshotStore also validates before writing.
        if(label!=null&&label.trim().length()>64)throw new IllegalArgumentException("Label exceeds 64 characters");
        synchronized(lifecycle){
            if(closed)throw new RejectedExecutionException("Snapshot service is closed");
            evictTerminated();
            if(jobs.size()>=maxRecentJobs){rejected.incrementAndGet();throw new RejectedExecutionException("Snapshot job history is full");}
            Job job=new Job(UUID.randomUUID().toString());
            jobs.put(job.id,job);
            try {workers.execute(new Capture(job,pid,label));}
            catch(RejectedExecutionException e){jobs.remove(job.id);rejected.incrementAndGet();throw e;}
            return job;
        }
    }

    /** Return a future without blocking the invoking daemon thread. */
    public CompletionStage<Snapshot> captureAsync(int pid,String label){return submit(pid,label).completion();}

    public Job status(String id){
        if(id==null||!id.matches("[a-fA-F0-9-]{36}"))return null;
        synchronized(lifecycle){return jobs.get(id);}
    }
    public long rejected(){return rejected.get();}
    public int pending(){return workers.getQueue().size();}
    public int active(){return workers.getActiveCount();}

    private void evictTerminated(){
        Iterator<Map.Entry<String,Job>> it=jobs.entrySet().iterator();
        while(it.hasNext()){
            Job j=it.next().getValue();
            if(j.isFinished() && (jobs.size()>maxRecentJobs/2 || System.currentTimeMillis()-j.requestedAt>15*60*1000L))it.remove();
        }
    }

    private final class Capture implements Runnable {
        private final Job job;
        private final int pid;
        private final String label;
        Capture(Job job,int pid,String label){this.job=job;this.pid=pid;this.label=label;}
        @Override public void run(){
            if(!job.running())return;
            try {
                // Each worker owns its probe instances. No sampler/OSHI mutable state is shared.
                ProcessVitals process=processProbes.get().read(pid);
                if(process==null)throw new IllegalArgumentException("Process is no longer available");
                HostVitals host=hostProbes.get().read();
                if(closed||Thread.currentThread().isInterrupted())throw new CancellationException("Service closed");
                // A one-shot reading has no process CPU delta; never invent an instantaneous %.
                Sample sample=new Sample(System.currentTimeMillis(),process.key,process.name,process.state,
                    Double.NaN,process.rssBytes,process.threads,process.readBytes,process.writeBytes,
                    process.majorFaults,Double.NaN,host.loadAverage,host.availableMemory,host.logicalCores);
                Snapshot snapshot=store.create(label,sample);
                job.success(snapshot);
            }catch(Exception | LinkageError e){job.failure(e instanceof CancellationException?"Cancelled":
                e instanceof IllegalArgumentException?e.getMessage():"Snapshot capture failed");}
        }
    }

    /** Snapshot job's mutable state is protected by its own monitor and published via volatile fields. */
    public static final class Job {
        public final String id;
        public final long requestedAt=System.currentTimeMillis();
        private final CompletableFuture<Snapshot> completion=new CompletableFuture<Snapshot>();
        private volatile String state="QUEUED";
        private volatile Snapshot result;
        private volatile String failure;
        private Job(String id){this.id=id;}
        private synchronized boolean running(){if(isFinished())return false;state="RUNNING";return true;}
        private synchronized void success(Snapshot snapshot){
            if(isFinished())return;
            result=snapshot;state="SUCCEEDED";completion.complete(snapshot);
        }
        private synchronized void failure(String reason){
            if(isFinished())return;
            failure=reason;state="FAILED";completion.completeExceptionally(new IllegalStateException(reason));
        }
        public boolean isFinished(){return "SUCCEEDED".equals(state)||"FAILED".equals(state);}
        public String state(){return state;}
        public CompletionStage<Snapshot> completion(){return completion;}
        public String json(){
            Snapshot s=result;
            return "{\"jobId\":"+Json.quote(id)+",\"status\":"+Json.quote(state)+
                ",\"requestedAt\":"+requestedAt+",\"snapshot\":"+(s==null?"null":s.json())+
                ",\"error\":"+(failure==null?"null":Json.quote(failure))+"}";
        }
    }

    /** Pending jobs fail promptly on shutdown; active workers are interrupted. Idempotent. */
    @Override public void close(){
        synchronized(lifecycle){
            if(closed)return;
            closed=true;
            workers.shutdownNow();
            for(Job job:jobs.values())if(!job.isFinished())job.failure("Snapshot service closed");
        }
    }
}
