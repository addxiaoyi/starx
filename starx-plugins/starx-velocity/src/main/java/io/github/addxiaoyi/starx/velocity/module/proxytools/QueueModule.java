/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.connection.DisconnectEvent
 *  com.velocitypowered.api.event.player.KickedFromServerEvent
 *  com.velocitypowered.api.event.player.KickedFromServerEvent$Notify
 *  com.velocitypowered.api.event.player.KickedFromServerEvent$ServerKickResult
 *  com.velocitypowered.api.event.player.ServerConnectedEvent
 *  com.velocitypowered.api.proxy.Player
 *  com.velocitypowered.api.proxy.ProxyServer
 *  com.velocitypowered.api.proxy.server.RegisteredServer
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
 */
package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.queue.QueueService;
import io.github.addxiaoyi.starx.velocity.routing.BackendRoutingService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class QueueModule
implements VelocityModule {
    private static final long CONNECTION_TIMEOUT_SECONDS = 10L;
    private final StarxVelocityPlugin plugin;
    private final Config config;
    private final QueueService queueService;
    private final QueueTargetPolicy targetPolicy;
    private ScheduledTask processingTask;
    private QueueListener listener;

    public QueueModule(
        StarxVelocityPlugin plugin,
        Config config,
        QueueService queueService,
        BackendRoutingService routingService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.queueService = Objects.requireNonNull(queueService, "queueService");
        BackendRoutingService routing = Objects.requireNonNull(routingService, "routingService");
        this.targetPolicy = new QueueTargetPolicy((preferred, queues) -> routing
            .select(preferred, queues)
            .map(decision -> decision.nodeId()));
    }

    @Override
    public String name() {
        return "starx.queue";
    }

    @Override
    public void onEnable() {
        ProxyServer proxy = this.plugin.proxy();
        QueueListener currentListener = new QueueListener();
        this.listener = currentListener;
        proxy.getEventManager().register((Object)this.plugin, (Object)currentListener);
        this.processingTask = proxy.getScheduler().buildTask((Object)this.plugin, this::processQueues)
            .repeat(Duration.ofMillis(this.config.checkIntervalMillis())).schedule();
    }

    public QueueService queueService() {
        return this.queueService;
    }

    @Override
    public void onDisable() {
        ScheduledTask current = this.processingTask;
        this.processingTask = null;
        if (current != null) current.cancel();
        QueueListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        this.queueService.clear();
    }

    public Map<String, Object> runtimeSnapshot() {
        Map<String, Object> servers = new LinkedHashMap<>();
        this.queueService.snapshot().forEach((server, size) -> servers.put(server, Map.of(
            "queued", size,
            "tailEtaSeconds", (size * this.config.checkIntervalMillis() + 999L) / 1000L)));
        return Map.of("mode", "fifo", "servers", Map.copyOf(servers));
    }

    void onKicked(KickedFromServerEvent event) {
        Optional reason = event.getServerKickReason();
        if (reason.isEmpty() || !this.isFullReason((Component)reason.get())) {
            return;
        }
        this.queueService.enqueue(event.getServer(), event.getPlayer());
        int position = this.queueService.position(event.getServer(), event.getPlayer());
        long eta = this.queueService.estimateWaitSeconds(
            event.getServer(), event.getPlayer(), 1, this.config.checkIntervalMillis());
        event.setResult((KickedFromServerEvent.ServerKickResult)KickedFromServerEvent.Notify.create(
            Component.text(this.config.queueMessage() + " 当前位置 #" + position + "，预计 " + eta + " 秒")));
    }

    void onServerConnected(ServerConnectedEvent event) {
        this.queueService.removeFromQueue(event.getServer(), event.getPlayer());
    }

    void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        this.queueService.removeFromAllQueues(player);
        player.getCurrentServer().ifPresent(connection -> this.plugin.proxy().getScheduler().buildTask((Object)this.plugin, () -> this.processQueueFor(connection.getServer())).schedule());
    }

    private void processQueueFor(RegisteredServer server) {
        this.processQueues();
    }

    void processQueues() {
        this.queueService.processQueues(this::connect);
    }

    private CompletableFuture<Boolean> connect(Player player, String serverName) {
        ProxyServer proxy = this.plugin.proxy();
        Optional<String> selected = this.targetPolicy.resolve(
            serverName, this.queueService.snapshot());
        if (selected.isEmpty()) return CompletableFuture.completedFuture(false);

        String selectedName = selected.get();
        RegisteredServer server = proxy.getServer(selectedName).orElse(null);
        if (server == null) return CompletableFuture.completedFuture(false);

        try {
            return player.createConnectionRequest(server).connect()
                .thenApply(result -> result.isSuccessful())
                .orTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(error -> {
                    this.plugin.logger().log(Level.FINE,
                        "Queue connection failed for player " + player.getUniqueId()
                            + " via " + selectedName,
                        error);
                    return false;
                });
        } catch (RuntimeException error) {
            this.plugin.logger().log(Level.FINE,
                "Queue connection could not start for player " + player.getUniqueId()
                    + " via " + selectedName,
                error);
            return CompletableFuture.completedFuture(false);
        }
    }

    private boolean isFullReason(Component reason) {
        String text = PlainTextComponentSerializer.plainText().serialize(reason).toLowerCase(Locale.ROOT);
        return this.config.fullPatterns().stream().anyMatch(text::contains);
    }

    public static interface Config {
        public Set<String> fullPatterns();

        public String queueMessage();

        public long checkIntervalMillis();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public Set<String> fullPatterns() {
                    return Set.of("full", "\u5df2\u6ee1", "is full");
                }

                @Override
                public String queueMessage() {
                    return "Server is full, you are queued.";
                }

                @Override
                public long checkIntervalMillis() {
                    return 3000L;
                }
            };
        }
    }

    private final class QueueListener {
        private QueueListener() {
        }

        @Subscribe
        public void onKicked(KickedFromServerEvent event) {
            QueueModule.this.onKicked(event);
        }

        @Subscribe
        public void onServerConnected(ServerConnectedEvent event) {
            QueueModule.this.onServerConnected(event);
        }

        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            QueueModule.this.onDisconnect(event);
        }
    }
}
