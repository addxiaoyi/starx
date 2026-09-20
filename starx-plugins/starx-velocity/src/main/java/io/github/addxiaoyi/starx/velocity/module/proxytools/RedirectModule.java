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
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.routing.BackendRoutingService;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class RedirectModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final Config config;
    private final BackendRoutingService routing;
    private final Predicate<com.velocitypowered.api.proxy.Player> authenticationPending;
    private final ConcurrentHashMap<UUID, Long> fallbackAttempts = new ConcurrentHashMap<>();
    private KickListener listener;

    public RedirectModule(
        StarxVelocityPlugin plugin,
        Config config,
        BackendRoutingService routing,
        Predicate<com.velocitypowered.api.proxy.Player> authenticationPending) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.authenticationPending = Objects.requireNonNull(authenticationPending, "authenticationPending");
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
        this.fallbackAttempts.clear();
    }

    void onKicked(KickedFromServerEvent event) {
        KickedFromServerEvent.ServerKickResult current = event.getResult();
        if (current == null) {
            return;
        }
        if (this.authenticationPending.test(event.getPlayer())) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        long now = System.nanoTime();
        Long previous = this.fallbackAttempts.put(playerId, now);
        if (previous != null && now - previous < Duration.ofSeconds(10).toNanos()) {
            return;
        }
        String failedName = event.getServer().getServerInfo().getName();
        Optional target = this.routing.selectAlternative(failedName, failedName, Map.of())
            .map(decision -> decision.nodeId())
            .flatMap(this.plugin.proxy()::getServer)
            .or(() -> this.plugin.proxy().getServer(this.config.targetServer()));
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

        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            RedirectModule.this.fallbackAttempts.remove(event.getPlayer().getUniqueId());
        }
    }
}
