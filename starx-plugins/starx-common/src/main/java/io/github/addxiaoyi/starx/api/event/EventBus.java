/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.api.event;

import io.github.addxiaoyi.starx.api.event.StarxEvent;
import java.util.Map;
import java.util.function.Consumer;

public interface EventBus {
    public void publish(StarxEvent var1);

    default public void publish(String type) {
        this.publish(new StarxEvent(type, Map.of()));
    }

    default public void publish(String type, Map<String, Object> payload) {
        this.publish(new StarxEvent(type, payload));
    }

    public void subscribe(String var1, Consumer<StarxEvent> var2);

    default public void unsubscribe(String type, Consumer<StarxEvent> subscriber) {
    }
}
