package io.quantlab.storage;

import io.quantlab.model.Sample;
import io.quantlab.model.ProcessKey;
import java.io.IOException;
import java.util.List;

public interface HistoryStore extends AutoCloseable {
    void append(Sample sample);
    List<Sample> query(ProcessKey key,long from,long until,int maximumPoints) throws IOException;
    long dropped();
    void close();
}
