/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.connection.LoginEvent
 *  com.velocitypowered.api.event.proxy.ProxyPingEvent
 */
package io.github.addxiaoyi.starx.velocity.module.security;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BotFilterModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final Config config;
    private static final int MAX_TRACKED_IPS = 4_096;
    private static final long WINDOW_MILLIS = 1_000L;
    private final PerIpWindowCounter pingTracker = new PerIpWindowCounter(MAX_TRACKED_IPS, WINDOW_MILLIS);
    private final PerIpWindowCounter connectionTracker = new PerIpWindowCounter(MAX_TRACKED_IPS, WINDOW_MILLIS);
    private ScheduledExecutorService scheduler;
    private PingListener pingListener;
    private LoginListener loginListener;

    public BotFilterModule(StarxVelocityPlugin plugin, EventBus eventBus, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.security.bot";
    }

    @Override
    public void onEnable() {
        PingListener currentPingListener = new PingListener();
        LoginListener currentLoginListener = new LoginListener();
        this.pingListener = currentPingListener;
        this.loginListener = currentLoginListener;
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentPingListener);
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentLoginListener);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "botfilter-purge");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::purgeExpired, 30L, 30L, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        if (this.pingListener != null) {
            this.plugin.proxy().getEventManager().unregisterListener(this.plugin, this.pingListener);
            this.pingListener = null;
        }
        if (this.loginListener != null) {
            this.plugin.proxy().getEventManager().unregisterListener(this.plugin, this.loginListener);
            this.loginListener = null;
        }
        this.pingTracker.clear();
        this.connectionTracker.clear();
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
            this.scheduler = null;
        }
    }

    int getPingCount(String ip) {
        return this.pingTracker.count(ip, System.currentTimeMillis());
    }

    int getConnectionCount(String ip) {
        return this.connectionTracker.count(ip, System.currentTimeMillis());
    }

    void onProxyPing(ProxyPingEvent event) {
        InetSocketAddress address = event.getConnection().getRemoteAddress();
        if (address == null) {
            return;
        }
        String ip = address.getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        int count = this.pingTracker.increment(ip, now);
        if (count > this.config.maxPingsPerSecond()) {
            this.eventBus.publish(new StarxEvent("security:bot:detected", Map.of("ip", ip, "reason", "ping_flood", "count", count, "limit", this.config.maxPingsPerSecond())));
        }
    }

    void onLogin(LoginEvent event) {
        InetSocketAddress address = event.getPlayer().getRemoteAddress();
        if (address == null) {
            return;
        }
        String ip = address.getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        int count = this.connectionTracker.increment(ip, now);
        if (count > this.config.maxConnectionsPerSecond()) {
            this.eventBus.publish(new StarxEvent("security:rate:limit:exceeded", Map.of("ip", ip, "username", event.getPlayer().getUsername(), "limit", this.config.maxConnectionsPerSecond())));
        }
    }

    void purgeExpired() {
        long now = System.currentTimeMillis();
        long cutoff = now - this.config.cachePurgeMillis();
        this.pingTracker.purgeBefore(cutoff);
        this.connectionTracker.purgeBefore(cutoff);
    }

    public static interface Config {
        public int maxPingsPerSecond();

        public int maxConnectionsPerSecond();

        public boolean checkClientBrand();

        public boolean checkClientSettings();

        public long cachePurgeMillis();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public int maxPingsPerSecond() {
                    return 20;
                }

                @Override
                public int maxConnectionsPerSecond() {
                    return 10;
                }

                @Override
                public boolean checkClientBrand() {
                    return true;
                }

                @Override
                public boolean checkClientSettings() {
                    return true;
                }

                @Override
                public long cachePurgeMillis() {
                    return 60000L;
                }
            };
        }
    }

    private final class PingListener {
        private PingListener() {
        }

        @Subscribe
        public void onProxyPing(ProxyPingEvent event) {
            BotFilterModule.this.onProxyPing(event);
        }
    }

    private final class LoginListener {
        private LoginListener() {
        }

        @Subscribe
        public void onLogin(LoginEvent event) {
            BotFilterModule.this.onLogin(event);
        }
    }

}
