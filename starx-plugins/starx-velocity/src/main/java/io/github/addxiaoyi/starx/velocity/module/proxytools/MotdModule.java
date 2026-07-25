/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.proxy.ProxyPingEvent
 *  net.kyori.adventure.text.Component
 */
package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;

public final class MotdModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final Config config;
    private volatile boolean maintenanceActive;
    private PingListener listener;
    private final Consumer<StarxEvent> maintenanceSubscriber = this::onMaintenanceChanged;

    public MotdModule(StarxVelocityPlugin plugin, EventBus eventBus, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.motd";
    }

    @Override
    public void onEnable() {
        PingListener currentListener = new PingListener();
        this.listener = currentListener;
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentListener);
        this.eventBus.subscribe("proxy:maintenance:changed", this.maintenanceSubscriber);
    }

    @Override
    public void onDisable() {
        this.eventBus.unsubscribe("proxy:maintenance:changed", this.maintenanceSubscriber);
        PingListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
    }

    public boolean isMaintenanceActive() {
        return this.maintenanceActive;
    }

    void onMaintenanceChanged(StarxEvent event) {
        Boolean enabled = (Boolean)event.get("enabled");
        this.maintenanceActive = enabled != null && enabled != false;
    }

    void onProxyPing(ProxyPingEvent event) {
        Component motd = this.maintenanceActive ? this.config.maintenanceMotd() : this.config.normalMotd();
        event.setPing(event.getPing().asBuilder().description(motd).build());
    }

    public static interface Config {
        public Component normalMotd();

        public Component maintenanceMotd();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public Component normalMotd() {
                    return Component.text((String)"欢迎来到 StarX！");
                }

                @Override
                public Component maintenanceMotd() {
                    return Component.text((String)"StarX 正在维护中。");
                }
            };
        }
    }

    private final class PingListener {
        private PingListener() {
        }

        @Subscribe
        public void onProxyPing(ProxyPingEvent event) {
            MotdModule.this.onProxyPing(event);
        }
    }
}
