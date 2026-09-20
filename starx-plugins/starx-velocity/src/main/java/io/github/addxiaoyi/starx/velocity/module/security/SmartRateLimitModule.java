/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.proxy.ProxyPingEvent
 *  com.velocitypowered.api.proxy.InboundConnection
 *  com.velocitypowered.api.proxy.server.ServerPing
 *  net.kyori.adventure.text.Component
 */
package io.github.addxiaoyi.starx.velocity.module.security;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.server.ServerPing;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.common.smart.AdaptiveRateLimiter;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;

public final class SmartRateLimitModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final AdaptiveRateLimiter rateLimiter;
    private ScheduledExecutorService scheduler;
    private PingListener listener;
    private LoginListener loginListener;
    private final PerIpWindowCounter pingTracker;
    private final PerIpWindowCounter connectionTracker;
    private static final int DEFAULT_MAX_CONN = 10;
    private static final int DEFAULT_MAX_PING = 20;
    private static final int SAMPLE_INTERVAL_SEC = 5;
    private static final int MAX_TRACKED_IPS = 4_096;
    private static final long WINDOW_MILLIS = 1_000L;
    private static final long TRACKER_RETENTION_MILLIS = 60_000L;

    public SmartRateLimitModule(StarxVelocityPlugin plugin, EventBus eventBus) {
        this(plugin, eventBus, 10, 20);
    }

    public SmartRateLimitModule(StarxVelocityPlugin plugin, EventBus eventBus, int defaultMaxConn, int defaultMaxPing) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.rateLimiter = new AdaptiveRateLimiter(defaultMaxConn, defaultMaxPing);
        this.pingTracker = new PerIpWindowCounter(MAX_TRACKED_IPS, WINDOW_MILLIS);
        this.connectionTracker = new PerIpWindowCounter(MAX_TRACKED_IPS, WINDOW_MILLIS);
    }

    @Override
    public String name() {
        return "starx.security.smart-rate";
    }

    @Override
    public void onEnable() {
        PingListener currentListener = new PingListener();
        LoginListener currentLoginListener = new LoginListener();
        this.listener = currentListener;
        this.loginListener = currentLoginListener;
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentListener);
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentLoginListener);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "starx-smart-rate");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::sampleMetrics, 5L, 5L, TimeUnit.SECONDS);
        this.plugin.logger().info("SmartRateLimit: started with conn=10 ping=20");
    }

    @Override
    public void onDisable() {
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
            this.scheduler = null;
        }
        PingListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) {
            this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        }
        LoginListener currentLoginListener = this.loginListener;
        this.loginListener = null;
        if (currentLoginListener != null) {
            this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentLoginListener);
        }
        this.pingTracker.clear();
        this.connectionTracker.clear();
    }

    private void sampleMetrics() {
        try {
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            int memPercent = (int)(used * 100L / rt.maxMemory());
            int playerCount = this.plugin.proxy().getPlayerCount();
            int estimatedTps = Math.max(5, 20 - playerCount / 20);
            this.rateLimiter.updateTps(estimatedTps);
            this.rateLimiter.updateMemoryPercent(memPercent);
            long cutoff = System.currentTimeMillis() - TRACKER_RETENTION_MILLIS;
            this.pingTracker.purgeBefore(cutoff);
            this.connectionTracker.purgeBefore(cutoff);
            AdaptiveRateLimiter.LoadLevel level = this.rateLimiter.evaluateLoad();
            if (level != AdaptiveRateLimiter.LoadLevel.NORMAL) {
                this.plugin.logger().fine("SmartRateLimit: load=" + String.valueOf((Object)level) + " tps\u2248" + estimatedTps + " mem=" + memPercent + "% maxConn=" + this.rateLimiter.maxConnectionsPerSecond() + " maxPing=" + this.rateLimiter.maxPingsPerSecond());
            }
        }
        catch (Exception e) {
            this.plugin.logger().log(Level.FINE, "SmartRateLimit: metric sampling failed", e);
        }
    }

    private boolean isPingRateLimited(String ip) {
        int max = this.rateLimiter.evaluateLoad() == AdaptiveRateLimiter.LoadLevel.CRITICAL ? Math.max(1, this.rateLimiter.maxPingsPerSecond()) : this.rateLimiter.maxPingsPerSecond();
        return this.pingTracker.increment(ip, System.currentTimeMillis()) > max;
    }

    private boolean isConnectionRateLimited(String ip) {
        int max = this.rateLimiter.maxConnectionsPerSecond();
        return this.connectionTracker.increment(ip, System.currentTimeMillis()) > max;
    }

    AdaptiveRateLimiter.LoadLevel getCurrentLoadLevel() {
        return this.rateLimiter.evaluateLoad();
    }

    int getMaxConnections() {
        return this.rateLimiter.maxConnectionsPerSecond();
    }

    int getMaxPings() {
        return this.rateLimiter.maxPingsPerSecond();
    }

    private final class PingListener {
        private PingListener() {
        }

        @Subscribe
        public void onProxyPing(ProxyPingEvent event) {
            InetSocketAddress addr;
            String ip;
            InboundConnection conn = event.getConnection();
            InetSocketAddress inetSocketAddress = conn.getRemoteAddress();
            if (inetSocketAddress instanceof InetSocketAddress && SmartRateLimitModule.this.isPingRateLimited(ip = (addr = inetSocketAddress).getAddress().getHostAddress())) {
                event.setPing(ServerPing.builder().description((Component)Component.text((String)"请求过于频繁，请稍后再试。")).build());
                SmartRateLimitModule.this.eventBus.publish("security:rate:limit:exceeded", Map.of("ip", ip, "type", "ping", "level", SmartRateLimitModule.this.rateLimiter.evaluateLoad().name()));
            }
        }
    }

    private final class LoginListener {
        @Subscribe(order = PostOrder.EARLY)
        public void onLogin(LoginEvent event) {
            InetSocketAddress address = event.getPlayer().getRemoteAddress();
            if (address == null || address.getAddress() == null) return;
            String ip = address.getAddress().getHostAddress();
            if (!SmartRateLimitModule.this.isConnectionRateLimited(ip)) return;
            event.setResult(ResultedEvent.ComponentResult.denied(
                Component.text("连接过于频繁，请稍后再试。")));
            SmartRateLimitModule.this.eventBus.publish(
                "security:rate:limit:exceeded",
                Map.of("ip", ip, "type", "connection",
                    "level", SmartRateLimitModule.this.rateLimiter.evaluateLoad().name()));
        }
    }
}
