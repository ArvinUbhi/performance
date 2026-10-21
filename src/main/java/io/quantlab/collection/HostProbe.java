package io.quantlab.collection;

import io.quantlab.model.HostVitals;

public interface HostProbe {
    HostVitals read() throws Exception;
    String implementation();
}
