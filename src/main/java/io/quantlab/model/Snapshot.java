package io.quantlab.model;

import io.quantlab.core.Json;

public final class Snapshot {
    public final String id,label;
    public final long capturedAt;
    public final Sample sample;
    public Snapshot(String id,String label,long capturedAt,Sample sample){
        this.id=id;this.label=label;this.capturedAt=capturedAt;this.sample=sample;
    }
    public String json(){return "{\"id\":"+Json.quote(id)+",\"label\":"+Json.quote(label)+
        ",\"capturedAt\":"+capturedAt+",\"sample\":"+sample.json()+"}";}
}
