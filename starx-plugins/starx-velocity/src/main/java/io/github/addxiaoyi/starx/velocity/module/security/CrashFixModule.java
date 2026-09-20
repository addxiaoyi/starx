/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.connection.PluginMessageEvent
 */
package io.github.addxiaoyi.starx.velocity.module.security;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Map;
import java.util.Objects;

public final class CrashFixModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final Config config;
    private PluginMessageListener listener;

    public CrashFixModule(StarxVelocityPlugin plugin, EventBus eventBus, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.security.crash";
    }

    @Override
    public void onEnable() {
        PluginMessageListener currentListener = new PluginMessageListener();
        this.listener = currentListener;
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentListener);
    }

    @Override
    public void onDisable() {
        PluginMessageListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) {
            this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        }
    }

    boolean checkPacketSize(int size) {
        if (size > this.config.maxPacketSize()) {
            this.eventBus.publish(new StarxEvent("security:crash:attempt", Map.of("reason", "oversized_packet", "size", size, "limit", this.config.maxPacketSize())));
            return true;
        }
        return false;
    }

    boolean checkNbtDepth(int depth) {
        if (depth > this.config.maxNbtDepth()) {
            this.eventBus.publish(new StarxEvent("security:crash:attempt", Map.of("reason", "nbt_overflow", "depth", depth, "limit", this.config.maxNbtDepth())));
            return true;
        }
        return false;
    }

    boolean checkArraySize(int size) {
        if (size < 0 || size > this.config.maxArraySize()) {
            this.eventBus.publish(new StarxEvent("security:packet:suspicious", Map.of("reason", "invalid_array_size", "size", size, "limit", this.config.maxArraySize())));
            return true;
        }
        return false;
    }

    public static interface Config {
        public int maxPacketSize();

        public int maxNbtDepth();

        public int maxArraySize();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public int maxPacketSize() {
                    return 0x500000;
                }

                @Override
                public int maxNbtDepth() {
                    return 128;
                }

                @Override
                public int maxArraySize() {
                    return 256;
                }
            };
        }
    }

    private final class PluginMessageListener {
        private PluginMessageListener() {
        }

        @Subscribe
        public void onPluginMessage(PluginMessageEvent event) {
            byte[] data = event.getData();
            if (data != null) {
                CrashFixModule.this.checkPacketSize(data.length);
            }
        }
    }
}
