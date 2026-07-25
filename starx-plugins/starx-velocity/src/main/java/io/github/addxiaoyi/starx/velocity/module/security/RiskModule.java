/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.connection.LoginEvent
 */
package io.github.addxiaoyi.starx.velocity.module.security;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.network.LocalAddressInfo;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RiskModule
implements VelocityModule {
    private static final int MAX_TRACKED_PLAYERS = 4_096;
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final Config config;
    private final DeviceRegistry deviceRegistry = new DeviceRegistry(MAX_TRACKED_PLAYERS);
    private LoginListener listener;

    public RiskModule(StarxVelocityPlugin plugin, EventBus eventBus, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.security.risk";
    }

    @Override
    public void onEnable() {
        LoginListener currentListener = new LoginListener();
        this.listener = currentListener;
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentListener);
    }

    @Override
    public void onDisable() {
        LoginListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) {
            this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        }
        this.deviceRegistry.clear();
    }

    int scoreIp(String ip) {
        return LocalAddressInfo.parse(ip).baseRiskScore();
    }

    boolean isHighRisk(int score) {
        return score >= this.config.highRiskThreshold();
    }

    boolean requiresTotp(int score) {
        return this.config.requireTotpForHighRisk() && this.isHighRisk(score);
    }

    boolean isNewDevice(UUID playerId, String ip) {
        String registeredIp = this.deviceRegistry.get(playerId);
        return registeredIp == null || !registeredIp.equals(ip);
    }

    void registerDevice(UUID playerId, String ip) {
        this.deviceRegistry.observe(playerId, ip, System.currentTimeMillis());
    }

    void onLogin(LoginEvent event) {
        InetSocketAddress address = event.getPlayer().getRemoteAddress();
        if (address == null) {
            return;
        }
        String ip = address.getAddress().getHostAddress();
        UUID playerId = event.getPlayer().getUniqueId();
        String username = event.getPlayer().getUsername();
        int riskScore = this.scoreIp(ip);
        if (this.config.checkNewDevice() && this.isNewDevice(playerId, ip)) {
            riskScore += 10;
        }
        if (this.isHighRisk(riskScore)) {
            this.eventBus.publish(new StarxEvent("security:risk:high", Map.of("uuid", playerId, "username", username, "ip", ip, "score", riskScore)));
            if (this.requiresTotp(riskScore)) {
                this.eventBus.publish(new StarxEvent("security:risk:verify:required", Map.of("uuid", playerId, "username", username, "ip", ip)));
            }
        }
        this.registerDevice(playerId, ip);
    }

    public static interface Config {
        public int highRiskThreshold();

        public boolean requireTotpForHighRisk();

        public boolean checkNewDevice();

        public boolean checkAsn();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public int highRiskThreshold() {
                    return 70;
                }

                @Override
                public boolean requireTotpForHighRisk() {
                    return false;
                }

                @Override
                public boolean checkNewDevice() {
                    return true;
                }

                @Override
                public boolean checkAsn() {
                    return false;
                }
            };
        }
    }

    private final class LoginListener {
        private LoginListener() {
        }

        @Subscribe
        public void onLogin(LoginEvent event) {
            RiskModule.this.onLogin(event);
        }
    }

    static final class DeviceRegistry {
        private final int capacity;
        private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

        DeviceRegistry(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
            this.capacity = capacity;
        }

        synchronized void observe(UUID playerId, String ip, long now) {
            this.entries.put(
                Objects.requireNonNull(playerId, "playerId"),
                new Entry(Objects.requireNonNull(ip, "ip"), now));
            while (this.entries.size() > this.capacity) {
                this.entries.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(entry -> entry.getValue().observedAt()))
                    .ifPresent(oldest -> this.entries.remove(oldest.getKey(), oldest.getValue()));
            }
        }

        String get(UUID playerId) {
            Entry entry = this.entries.get(playerId);
            return entry == null ? null : entry.ip();
        }

        int size() { return this.entries.size(); }
        void clear() { this.entries.clear(); }

        private record Entry(String ip, long observedAt) { }
    }
}
