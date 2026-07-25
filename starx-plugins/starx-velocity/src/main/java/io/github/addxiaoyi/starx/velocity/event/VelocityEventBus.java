/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.event;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class VelocityEventBus
implements EventBus, AutoCloseable {
    private static final int ASYNC_THREADS = 2;
    private static final System.Logger LOG = System.getLogger(VelocityEventBus.class.getName());
    private final Map<String, List<Consumer<StarxEvent>>> subscribers = new ConcurrentHashMap<String, List<Consumer<StarxEvent>>>(32);
    private final List<Consumer<StarxEvent>> observers = new CopyOnWriteArrayList<>();
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "starx-event-bus");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public void publish(StarxEvent event) {
        if (this.closed.get()) {
            return;
        }
        List<Consumer<StarxEvent>> listeners = this.subscribers.getOrDefault(event.type(), List.of());
        if (listeners.isEmpty() && this.observers.isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            for (Consumer<StarxEvent> observer : this.observers) {
                this.deliver(observer, event);
            }
            for (Consumer<StarxEvent> listener : listeners) {
                this.deliver(listener, event);
            }
        }, this.asyncExecutor);
    }

    public void subscribeAll(Consumer<StarxEvent> observer) {
        if (this.closed.get()) throw new IllegalStateException("StarX event bus is closed");
        this.observers.add(java.util.Objects.requireNonNull(observer, "observer"));
    }

    public void unsubscribeAll(Consumer<StarxEvent> observer) {
        this.observers.remove(observer);
    }

    private void deliver(Consumer<StarxEvent> listener, StarxEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING,
                "StarX event subscriber failed for " + event.type(), error);
        }
    }

    @Override
    public void subscribe(String type, Consumer<StarxEvent> subscriber) {
        if (this.closed.get()) throw new IllegalStateException("StarX event bus is closed");
        this.subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList()).add(subscriber);
    }

    @Override
    public void unsubscribe(String type, Consumer<StarxEvent> subscriber) {
        this.subscribers.computeIfPresent(type, (k, list) -> {
            list.remove(subscriber);
            return list.isEmpty() ? null : list;
        });
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.subscribers.clear();
        this.observers.clear();
        this.asyncExecutor.shutdownNow();
    }
}
