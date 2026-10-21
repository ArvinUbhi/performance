package io.quantlab.collection;

import io.quantlab.model.ProcessVitals;

/** Strategy pattern: isolate process instrumentation from daemon lifecycle and storage. */
public interface ProcessProbe {
    ProcessVitals read(int pid) throws Exception;
    String implementation();
}
