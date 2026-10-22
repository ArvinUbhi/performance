import io.quantlab.collection.HostProbe;
import io.quantlab.collection.ProcessProbe;
import io.quantlab.core.SnapshotService;
import io.quantlab.model.*;
import io.quantlab.storage.SnapshotStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Java-8 compatible, dependency-free stress suite. */
public final class SnapshotConcurrencySelfTest {
    private static void check(boolean x,String message){if(!x)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("quantlab-concurrency-");
        SnapshotStore store=new SnapshotStore(root.resolve("snapshots"),20);
        CountDownLatch entered=new CountDownLatch(1);
        CountDownLatch release=new CountDownLatch(1);
        AtomicInteger active=new AtomicInteger();
        AtomicInteger maximum=new AtomicInteger();
        Set<String> threads=Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
        ProcessProbe probe=new ProcessProbe(){
            public String implementation(){return "fake";}
            public ProcessVitals read(int pid) throws Exception {
                threads.add(Thread.currentThread().getName());
                int current=active.incrementAndGet();maximum.accumulateAndGet(current,Math::max);
                try {
                    if(pid==81){entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Test latch timed out");}
                    return new ProcessVitals(new ProcessKey(pid,5000L),"worker","RUNNING",42,2048,3,1,2,0);
                }finally{active.decrementAndGet();}
            }
        };
        HostProbe host=new HostProbe(){public String implementation(){return "fake";}
            public HostVitals read(){return new HostVitals(20,1.4,1000000,4);}};
        SnapshotService service=new SnapshotService(()->probe,()->host,store,1,1,4);
        long before=System.nanoTime();
        SnapshotService.Job first=service.submit(81,"one");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<500,"snapshot submission must not wait on probe");
        check(entered.await(2,TimeUnit.SECONDS),"worker reached the blocked probe");
        SnapshotService.Job second=service.submit(82,"two");
        check("QUEUED".equals(second.state()),"second job queued");
        boolean rejected=false;
        try{service.submit(83,"too-many");}
        catch(RejectedExecutionException expected){rejected=true;}
        check(rejected && service.rejected()==1,"bounded queue rejects overload");
        release.countDown();
        Snapshot a=first.completion().toCompletableFuture().get(3,TimeUnit.SECONDS);
        Snapshot b=second.completion().toCompletableFuture().get(3,TimeUnit.SECONDS);
        check(a.sample.key.pid==81&&b.sample.key.pid==82,"async results belong to correct requests");
        check(threads.size()==1&&threads.iterator().next().startsWith("quantlab-snapshot-"),"dedicated snapshot thread");
        check(maximum.get()==1,"concurrency bounded by worker count");
        check("SUCCEEDED".equals(service.status(first.id).state()),"completed status visible");
        check(Double.isNaN(a.sample.cpuPercent),"a single read is not a CPU-rate measurement");
        service.close();service.close();
        boolean closedReject=false;
        try{service.submit(84,"closed");}catch(RejectedExecutionException expected){closedReject=true;}
        check(closedReject,"shutdown refuses new work");
        SnapshotStore stress=new SnapshotStore(root.resolve("stress"),20);
        ExecutorService workers=Executors.newFixedThreadPool(8);
        List<Future<?>> writes=new ArrayList<Future<?>>();
        for(int n=0;n<80;n++){
            final int index=n;
            writes.add(workers.submit(()->{
                try {
                    Sample sample=new Sample(10000+index,new ProcessKey(100+index,5000),"worker","R",Double.NaN,512,1,0,0,0,Double.NaN,1,100,4);
                    Snapshot snapshot=stress.create("c-"+index,sample);
                    Snapshot loaded=stress.get(snapshot.id);
                    // Retention may already have evicted this snapshot by another concurrent writer.
                    if(loaded!=null)check(loaded.id.equals(snapshot.id),"snapshot atomic read");
                    check(stress.list(0).size()<=20,"bounded snapshot retention");
                }catch(Exception ex){throw new RuntimeException(ex);}
            }));
        }
        for(Future<?> f:writes)f.get(15,TimeUnit.SECONDS);
        workers.shutdownNow();
        check(stress.list(0).size()==20,"retention enforced with concurrent creators");
        // Close with a queued job: futures must complete exceptionally, not hang forever.
        CountDownLatch blocking=new CountDownLatch(1),started=new CountDownLatch(1);
        SnapshotService closing=new SnapshotService(()->new ProcessProbe(){
            public String implementation(){return "blocking";}
            public ProcessVitals read(int pid) throws Exception {started.countDown();blocking.await();return null;}
        },()->host,store,1,1,4);
        SnapshotService.Job running=closing.submit(91,"running");
        check(started.await(2,TimeUnit.SECONDS),"started before shutdown");
        SnapshotService.Job queued=closing.submit(92,"queued");
        closing.close();blocking.countDown();
        check(running.completion().toCompletableFuture().isCompletedExceptionally(),"running cancelled on shutdown");
        check(queued.completion().toCompletableFuture().isCompletedExceptionally(),"queued cancelled on shutdown");
        System.out.println("PASS: dedicated worker isolation; asynchronous admission; bounded queue; overload; atomic store; retention; shutdown; no future leaks");
    }
}
