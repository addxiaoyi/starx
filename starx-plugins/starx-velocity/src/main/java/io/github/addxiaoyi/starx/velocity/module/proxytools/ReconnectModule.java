/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.connection.DisconnectEvent
 *  com.velocitypowered.api.event.connection.LoginEvent
 *  com.velocitypowered.api.proxy.Player
 *  com.velocitypowered.api.proxy.ProxyServer
 */
package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.routing.BackendRoutingService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReconnectModule
implements VelocityModule {
    private static final int MAX_PENDING_RECONNECTS = 4_096;
    private final StarxVelocityPlugin plugin;
    private final Config config;
    private final ReconnectTargetStore lastServers = new ReconnectTargetStore(MAX_PENDING_RECONNECTS);
    private final ConcurrentHashMap<UUID, Player> activePlayers = new ConcurrentHashMap<>();
    private final ReconnectTargetPolicy targetPolicy;
    private ReconnectListener listener;

    public ReconnectModule(
        StarxVelocityPlugin plugin,
        Config config,
        BackendRoutingService routingService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        BackendRoutingService routing = Objects.requireNonNull(routingService, "routingService");
        this.targetPolicy = new ReconnectTargetPolicy(preferred -> routing
            .select(preferred, java.util.Map.of())
            .map(decision -> decision.nodeId()));
    }

    @Override
    public String name() {
        return "starx.reconnect";
    }

    @Override
    public void onEnable() {
        ReconnectListener currentListener = new ReconnectListener();
        this.listener = currentListener;
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentListener);
        this.plugin.proxy().getAllPlayers().forEach(player ->
            this.activePlayers.put(player.getUniqueId(), player));
    }

    @Override
    public void onDisable() {
        ReconnectListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        this.lastServers.clear();
        this.activePlayers.clear();
    }

    public Optional<String> getLastServer(UUID playerUuid) {
        return this.lastServers.peek(playerUuid);
    }

    void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!this.detachActivePlayer(playerId, player)) return;
        if (!this.config.enabled()) {
            return;
        }
        player.getCurrentServer().ifPresent(connection -> this.lastServers.remember(
            playerId, connection.getServerInfo().getName(), System.currentTimeMillis()));
    }

    void onPostLogin(PostLoginEvent event) {
        this.activePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
    }

    private boolean detachActivePlayer(UUID playerId, Player player) {
        java.util.concurrent.atomic.AtomicBoolean removed =
            new java.util.concurrent.atomic.AtomicBoolean();
        this.activePlayers.compute(playerId, (ignored, current) -> {
            if (current == player) {
                removed.set(true);
                return null;
            }
            return current;
        });
        return removed.get();
    }

    void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (!this.config.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<String> remembered = this.lastServers.consume(player.getUniqueId());
        if (remembered.isEmpty()) {
            return;
        }
        Optional<String> selected = this.targetPolicy.resolve(remembered.get());
        if (selected.isEmpty()) {
            return;
        }
        String lastServerName = selected.get();
        ProxyServer proxy = this.plugin.proxy();
        proxy.getServer(lastServerName).ifPresent(event::setInitialServer);
    }

    public static interface Config {
        public boolean enabled();

        public static Config defaultConfig() {
            return () -> true;
        }
    }

    private final class ReconnectListener {
        private ReconnectListener() {
        }

        @Subscribe
        public void onPostLogin(PostLoginEvent event) {
            ReconnectModule.this.onPostLogin(event);
        }

        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            ReconnectModule.this.onDisconnect(event);
        }

        @Subscribe(order = PostOrder.FIRST)
        public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
            ReconnectModule.this.onChooseInitialServer(event);
        }
    }
}
