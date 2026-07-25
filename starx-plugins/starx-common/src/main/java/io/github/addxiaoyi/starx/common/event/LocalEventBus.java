/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.event;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class LocalEventBus
implements EventBus {
    private final Map<String, List<Consumer<StarxEvent>>> listeners = new ConcurrentHashMap<String, List<Consumer<StarxEvent>>>();

    @Override
    public void subscribe(String type, Consumer<StarxEvent> subscriber) {
        this.listeners.computeIfAbsent(type, key -> new CopyOnWriteArrayList()).add(subscriber);
    }

    @Override
    public void unsubscribe(String type, Consumer<StarxEvent> subscriber) {
        this.listeners.computeIfPresent(type, (key, subscribers) -> {
            subscribers.remove(subscriber);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }

    @Override
    public void publish(StarxEvent event) {
        List<Consumer<StarxEvent>> subscribers = this.listeners.get(event.type());
        if (subscribers == null) {
            return;
        }
        for (Consumer<StarxEvent> subscriber : subscribers) {
            subscriber.accept(event);
        }
    }
}
