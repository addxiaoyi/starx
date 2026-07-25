/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.player.KickedFromServerEvent
 *  com.velocitypowered.api.event.player.KickedFromServerEvent$RedirectPlayer
 *  com.velocitypowered.api.event.player.KickedFromServerEvent$ServerKickResult
 *  com.velocitypowered.api.proxy.server.RegisteredServer
 */
package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Objects;
import java.util.Optional;

public final class RedirectModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final Config config;
    private KickListener listener;

    public RedirectModule(StarxVelocityPlugin plugin, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.redirect";
    }

    @Override
    public void onEnable() {
        KickListener currentListener = new KickListener();
        this.listener = currentListener;
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentListener);
    }

    @Override
    public void onDisable() {
        KickListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
    }

    void onKicked(KickedFromServerEvent event) {
        KickedFromServerEvent.ServerKickResult current = event.getResult();
        if (current == null) {
            return;
        }
        Optional target = this.plugin.proxy().getServer(this.config.targetServer());
        if (target.isEmpty()) {
            return;
        }
        RegisteredServer targetServer = (RegisteredServer)target.get();
        if (event.getServer().equals((Object)targetServer)) {
            return;
        }
        event.setResult(KickedFromServerEvent.RedirectPlayer.create((RegisteredServer)targetServer));
    }

    public static interface Config {
        public String targetServer();

        public static Config defaultConfig() {
            return () -> "lobby";
        }
    }

    private final class KickListener {
        private KickListener() {
        }

        @Subscribe
        public void onKicked(KickedFromServerEvent event) {
            RedirectModule.this.onKicked(event);
        }
    }
}
