package io.quantlab;

import io.quantlab.core.Json;
import io.quantlab.model.*;
import io.quantlab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class PerformanceLabTest {
    @TempDir Path temp;
    @Test public void processIdentityGuardsPidReuse() {
        assertNotEquals(new ProcessKey(100,1000),new ProcessKey(100,1001));
        assertEquals(new ProcessKey(100,1000),new ProcessKey(100,1000));
    }
    @Test public void sampleRoundTripsAndEscapesMetadata() {
        Sample input=new Sample(System.currentTimeMillis(),new ProcessKey(12,1000),"newline\n\t\"<script>","R",12.5,1234,7,-1,0,0,50.3,2.3,80000,16);
        assertEquals(input.line(),Sample.parse(input.line()).line());
        assertTrue(input.json().contains("\\n"));
        assertEquals("null",Json.number(Double.NaN));
    }
    @Test public void snapshotsSurviveRestartAndCompare() throws Exception {
        SnapshotStore store=new SnapshotStore(temp.resolve("snapshots"),2);
        ProcessKey key=new ProcessKey(5,5000);
        Sample first=new Sample(10000,key,"example","RUNNING",10,100,5,0,0,0,40,1,100,4);
        Sample second=new Sample(20000,key,"example","RUNNING",30,300,8,0,0,0,70,2,100,4);
        Snapshot a=store.create("baseline",first);
        Snapshot b=store.create("candidate",second);
        assertEquals("baseline",new SnapshotStore(temp.resolve("snapshots"),2).get(a.id).label);
        assertTrue(SnapshotStore.compare(a,b).contains("\"rssDelta\":200"));
        assertEquals(2,store.list(5).size());
        store.create("third",second);
        assertEquals(2,store.list(5).size());
    }
    @Test public void historyPersistsAndDownsamples() throws Exception {
        Path directory=temp.resolve("history");ProcessKey key=new ProcessKey(8,8000);
        FileHistoryStore store=new FileHistoryStore(directory,4,7,16*1024*1024);
        long now=System.currentTimeMillis();
        for(int i=0;i<80;i++)store.append(new Sample(now+i,key,"sample","R",10,400,2,0,0,0,40,1,40000,4));
        store.close();
        FileHistoryStore reopened=new FileHistoryStore(directory,4,7,16*1024*1024);
        List<Sample> selected=reopened.query(key,now-1,now+1000,10);
        assertEquals(10,selected.size());
        assertEquals(now+79,selected.get(selected.size()-1).timestamp);
        reopened.close();
    }
}
