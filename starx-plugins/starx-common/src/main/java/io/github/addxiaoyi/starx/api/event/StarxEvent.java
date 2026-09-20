/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.api.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class StarxEvent {
    private final String type;
    private final UUID eventId;
    private final Instant timestamp;
    private final Map<String, Object> payload;

    public StarxEvent(String type, Map<String, Object> payload) {
        this(type, UUID.randomUUID(), Instant.now(), payload);
    }

    public StarxEvent(String type, UUID eventId, Instant timestamp, Map<String, Object> payload) {
        this.type = Objects.requireNonNull(type, "type");
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public String type() {
        return this.type;
    }

    public UUID eventId() {
        return this.eventId;
    }

    public Instant timestamp() {
        return this.timestamp;
    }

    public Map<String, Object> payload() {
        return this.payload;
    }

    public <T> T get(String key) {
        return (T)this.payload.get(key);
    }
}
