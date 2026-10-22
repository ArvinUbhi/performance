package io.quantlab;

import io.quantlab.collection.HostProbe;
import io.quantlab.collection.ProcessProbe;
import io.quantlab.core.SnapshotService;
import io.quantlab.model.*;
import io.quantlab.storage.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

public class SnapshotServiceTest {
    @TempDir Path temp;
    private static HostProbe host(){return new HostProbe(){
        public String implementation(){return "test";}
        public HostVitals read(){return new HostVitals(50,1.5,10000,8);}
    };}
    @Test public void requestsAreAsynchronousBoundedAndIsolated() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger inFlight=new AtomicInteger(),max=new AtomicInteger();
        Supplier<ProcessProbe> processes=()->new ProcessProbe(){
            public String implementation(){return "test";}
            public ProcessVitals read(int pid) throws Exception {
                assertTrue(Thread.currentThread().getName().startsWith("quantlab-snapshot-"));
                int n=inFlight.incrementAndGet();max.accumulateAndGet(n,Math::max);
                try {
                    if(pid==11){entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));}
                    return new ProcessVitals(new ProcessKey(pid,12345),"test","RUNNING",100,123,3,0,0,0);
                }finally{inFlight.decrementAndGet();}
            }
        };
        try(SnapshotService service=new SnapshotService(processes,SnapshotServiceTest::host,
                new SnapshotStore(temp.resolve("snapshots"),10),1,1,4)) {
            SnapshotService.Job first=service.submit(11,"first");
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            SnapshotService.Job second=service.submit(12,"second");
            assertEquals("QUEUED",second.state());
            assertThrows(RejectedExecutionException.class,()->service.submit(13,"overflow"));
            assertEquals(1,service.rejected());
            release.countDown();
            assertEquals(11,first.completion().toCompletableFuture().get(3,TimeUnit.SECONDS).sample.key.pid);
            assertEquals(12,second.completion().toCompletableFuture().get(3,TimeUnit.SECONDS).sample.key.pid);
            assertEquals(1,max.get());
            assertEquals("SUCCEEDED",service.status(first.id).state());
            assertTrue(Double.isNaN(service.status(first.id).completion().toCompletableFuture().get().sample.cpuPercent));
        }
    }

    @Test public void queuedAndRunningFuturesTerminateOnClose() throws Exception {
        CountDownLatch started=new CountDownLatch(1),block=new CountDownLatch(1);
        SnapshotService service=new SnapshotService(()->new ProcessProbe(){
            public String implementation(){return "test";}
            public ProcessVitals read(int pid)throws Exception{started.countDown();block.await();return null;}
        },SnapshotServiceTest::host,new SnapshotStore(temp.resolve("snapshots"),10),1,1,4);
        SnapshotService.Job a=service.submit(100,"a");
        assertTrue(started.await(2,TimeUnit.SECONDS));
        SnapshotService.Job b=service.submit(101,"b");
        service.close();block.countDown();
        assertTrue(a.completion().toCompletableFuture().isCompletedExceptionally());
        assertTrue(b.completion().toCompletableFuture().isCompletedExceptionally());
        assertThrows(RejectedExecutionException.class,()->service.submit(102,"closed"));
    }
}
