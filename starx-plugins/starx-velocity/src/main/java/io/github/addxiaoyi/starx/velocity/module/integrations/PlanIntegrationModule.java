/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.scheduler.ScheduledTask
 */
package io.github.addxiaoyi.starx.velocity.module.integrations;

import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.messaging.VelocityMessageBridge;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class PlanIntegrationModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final VelocityMessageBridge messageBridge;
    private final Config config;
    private final AtomicBoolean collecting = new AtomicBoolean(false);
    private final ConcurrentLinkedDeque<Map<String, Object>> dataPoints = new ConcurrentLinkedDeque<>();
    private final Map<String, Map<String, Object>> backendStats = new ConcurrentHashMap<>();
    private volatile int dataPointsCount = 0;
    private ScheduledTask scheduledTask;
    private final Consumer<StarxEvent> statsSubscriber = this::onBackendStats;

    public PlanIntegrationModule(StarxVelocityPlugin plugin, EventBus eventBus, VelocityMessageBridge messageBridge, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.messageBridge = Objects.requireNonNull(messageBridge, "messageBridge");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.integrations.plan";
    }

    @Override
    public void onEnable() {
        if (!this.config.enabled()) {
            return;
        }
        this.collecting.set(true);
        this.eventBus.subscribe("plan:stats:report", this.statsSubscriber);
        this.scheduledTask = this.plugin.proxy().getScheduler().buildTask((Object)this.plugin, this::collectDataPoint).repeat((long)this.config.collectIntervalSec(), TimeUnit.SECONDS).schedule();
    }

    @Override
    public void onDisable() {
        this.collecting.set(false);
        this.eventBus.unsubscribe("plan:stats:report", this.statsSubscriber);
        if (this.scheduledTask != null) {
            this.scheduledTask.cancel();
            this.scheduledTask = null;
        }
        this.backendStats.clear();
        this.dataPoints.clear();
        this.dataPointsCount = 0;
    }

    public boolean isCollecting() {
        return this.collecting.get();
    }

    void onBackendStats(StarxEvent event) {
        Map<String, Object> payload = event.payload();
        String serverName = String.valueOf(payload.getOrDefault("server", "unknown"));
        backendStats.put(serverName, payload);
    }

    public void collectDataPoint() {
        int excess;
        int onlinePlayers = this.plugin.proxy().getPlayerCount();
        LinkedHashMap<String, Object> point = new LinkedHashMap<>();
        point.put("timestamp", Instant.now().toString());
        point.put("online_players", onlinePlayers);
        point.put("server_count", this.plugin.proxy().getAllServers().size());
        point.put("backend_stats", new LinkedHashMap<>(this.backendStats));
        this.dataPoints.add(point);
        this.dataPointsCount++;
        if (this.dataPointsCount > this.config.maxDataPoints() && (excess = this.dataPointsCount - this.config.maxDataPoints()) > 0) {
            for (int i = 0; i < excess; i++) {
                this.dataPoints.pollFirst();
            }
            this.dataPointsCount = this.config.maxDataPoints();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public List<Map<String, Object>> getDataPoints() {
        return List.copyOf(this.dataPoints);
    }

    public Map<String, Map<String, Object>> getBackendStats() {
        return Map.copyOf(this.backendStats);
    }

    public Map<String, Object> getSnapshot() {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("online_players", this.plugin.proxy().getPlayerCount());
        snapshot.put("data_points", this.getDataPoints());
        snapshot.put("collect_interval_sec", this.config.collectIntervalSec());
        snapshot.put("backends", this.getBackendStats());
        return snapshot;
    }

    public Map<String, Object> getSummary() {
        return summarizeDataPoints(this.getDataPoints());
    }

    static Map<String, Object> summarizeDataPoints(List<? extends Map<String, ?>> points) {
        Objects.requireNonNull(points, "points");
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleCount", points.size());
        Object lastTimestamp = points.isEmpty() ? null : points.get(points.size() - 1).get("timestamp");
        String lastCollectedAt = lastTimestamp == null ? "" : String.valueOf(lastTimestamp);
        summary.put("lastCollectedAt", lastCollectedAt);
        return Map.copyOf(summary);
    }

    public static interface Config {
        public boolean enabled();

        public int collectIntervalSec();

        public int maxDataPoints();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public int collectIntervalSec() {
                    return 60;
                }

                @Override
                public int maxDataPoints() {
                    return 10080;
                }
            };
        }
    }
}
