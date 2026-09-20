/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.module.security;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.function.Consumer;

public final class SmartAlertModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final Map<String, AlertBucket> buckets;
    private ScheduledExecutorService cleaner;
    private final Consumer<StarxEvent> securitySubscriber = this::onSecurityEvent;
    private static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(5L);
    private static final int WARNING_THRESHOLD = 3;
    private static final int CRITICAL_THRESHOLD = 10;
    private static final long CLEAN_INTERVAL_SEC = 60L;
    private static final String[] SUBSCRIBED_EVENTS = new String[]{"security:alert", "security:bot:detected", "security:crash:attempt", "security:risk:high", "security:risk:verify:required", "security:rate:limit:exceeded", "security:packet:suspicious", "security:anticheat:detection"};

    public SmartAlertModule(StarxVelocityPlugin plugin, EventBus eventBus) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.buckets = new ConcurrentHashMap<String, AlertBucket>();
    }

    @Override
    public String name() {
        return "starx.security.smart-alert";
    }

    @Override
    public void onEnable() {
        for (String eventType : SUBSCRIBED_EVENTS) {
            this.eventBus.subscribe(eventType, this.securitySubscriber);
        }
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "starx-smart-alert-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::cleanExpired, 60L, 60L, TimeUnit.SECONDS);
        this.plugin.logger().info("SmartAlert: monitoring " + SUBSCRIBED_EVENTS.length + " event types");
    }

    @Override
    public void onDisable() {
        for (String eventType : SUBSCRIBED_EVENTS) {
            this.eventBus.unsubscribe(eventType, this.securitySubscriber);
        }
        if (this.cleaner != null) {
            this.cleaner.shutdownNow();
            this.cleaner = null;
        }
        this.buckets.clear();
    }

    private void onSecurityEvent(StarxEvent event) {
        try {
            String rawIp = (String)event.get("ip");
            String ip = rawIp != null ? rawIp : "unknown";
            String bucketKey = event.type() + ":" + ip;
            AlertBucket bucket = this.buckets.compute(bucketKey, (k, v) -> {
                if (v == null || v.isExpired()) {
                    return new AlertBucket(event.type(), ip);
                }
                v.increment();
                return v;
            });
            if (bucket.count >= 10 && !bucket.criticalEmitted) {
                bucket.criticalEmitted = true;
                this.plugin.logger().log(Level.SEVERE, "CRITICAL: {0} from {1} ({2} times in {3}m)", new Object[]{event.type(), ip, bucket.count, WINDOW_MS / 60000L});
                this.eventBus.publish("security:alert", Map.of("ip", ip, "type", event.type(), "severity", "CRITICAL", "count", String.valueOf(bucket.count)));
            } else if (bucket.count >= 3 && !bucket.warningEmitted) {
                bucket.warningEmitted = true;
                this.plugin.logger().log(Level.WARNING, "WARNING: {0} from {1} ({2} times)", new Object[]{event.type(), ip, bucket.count});
            }
        }
        catch (Exception e) {
            this.plugin.logger().log(Level.FINE, "SmartAlert: processing failed", e);
        }
    }

    private void cleanExpired() {
        this.buckets.entrySet().removeIf(e -> ((AlertBucket)e.getValue()).isExpired());
    }

    int getBucketCount() {
        return this.buckets.size();
    }

    void clearBuckets() {
        this.buckets.clear();
    }

    private static final class AlertBucket {
        final String eventType;
        final String ip;
        final long startMs;
        int count;
        boolean warningEmitted;
        boolean criticalEmitted;

        AlertBucket(String eventType, String ip) {
            this.eventType = eventType;
            this.ip = ip;
            this.startMs = System.currentTimeMillis();
            this.count = 1;
        }

        void increment() {
            ++this.count;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - this.startMs > WINDOW_MS;
        }
    }
}
