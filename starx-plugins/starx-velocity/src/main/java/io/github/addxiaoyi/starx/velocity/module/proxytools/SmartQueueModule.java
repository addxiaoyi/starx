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
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.addxiaoyi.starx.common.smart.AdaptiveRateLimiter;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.smart.SmartQueueService;
import io.github.addxiaoyi.starx.velocity.routing.BackendRoutingService;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class SmartQueueModule
implements VelocityModule {
    private static final long CONNECTION_TIMEOUT_SECONDS = 10L;
    private final StarxVelocityPlugin plugin;
    private final Config config;
    private final SmartQueueService queueService;
    private final BackendRoutingService routingService;
    private final QueueTargetPolicy targetPolicy;
    private final AdaptiveRateLimiter rateLimiter;
    private ScheduledTask processingTask;
    private SmartQueueListener listener;
    private static final int DEFAULT_CHECK_INTERVAL_MS = 3000;
    private static final int VIP_BASE_SCORE = 500;
    private static final int NORMAL_BASE_SCORE = 100;

    public SmartQueueModule(
        StarxVelocityPlugin plugin,
        Config config,
        SmartQueueService queueService,
        BackendRoutingService routingService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.queueService = Objects.requireNonNull(queueService, "queueService");
        this.routingService = Objects.requireNonNull(routingService, "routingService");
        this.targetPolicy = new QueueTargetPolicy((preferred, queues) -> this.routingService
            .select(preferred, queues)
            .map(decision -> decision.nodeId()));
        this.rateLimiter = new AdaptiveRateLimiter(5, 10);
    }

    @Override
    public String name() {
        return "starx.proxytools.smart-queue";
    }

    @Override
    public void onEnable() {
        ProxyServer proxy = this.plugin.proxy();
        SmartQueueListener currentListener = new SmartQueueListener();
        this.listener = currentListener;
        proxy.getEventManager().register((Object)this.plugin, (Object)currentListener);
        this.processingTask = proxy.getScheduler().buildTask((Object)this.plugin, this::sampleAndProcess)
            .repeat(Duration.ofMillis(this.config.checkIntervalMillis())).schedule();
        this.plugin.logger().info("SmartQueue: VIP priority + dynamic release enabled");
    }

    public SmartQueueService queueService() {
        return this.queueService;
    }

    @Override
    public void onDisable() {
        ScheduledTask current = this.processingTask;
        this.processingTask = null;
        if (current != null) current.cancel();
        SmartQueueListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        this.queueService.clear();
    }

    public Map<String, Object> runtimeSnapshot() {
        AdaptiveRateLimiter.LoadLevel load = this.rateLimiter.evaluateLoad();
        int release = Math.max(1, this.releaseRate(load));
        Map<String, Object> servers = new LinkedHashMap<>();
        this.queueService.snapshot().forEach((server, size) -> {
            long cycles = (size + (long) release - 1L) / release;
            servers.put(server, Map.of(
                "queued", size,
                "tailEtaSeconds", (cycles * this.config.checkIntervalMillis() + 999L) / 1000L));
        });
        return Map.of(
            "mode", "priority",
            "load", load.name(),
            "releasePerCycle", release,
            "servers", Map.copyOf(servers));
    }

    void onLogin(Player player) {
        this.queueService.recordJoin(player);
    }

    void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        this.queueService.recordQuit(player);
        player.getCurrentServer().ifPresent(connection -> this.plugin.proxy().getScheduler().buildTask((Object)this.plugin, () -> this.processQueues()).schedule());
    }

    void onKicked(KickedFromServerEvent event) {
        Optional reason = event.getServerKickReason();
        if (reason.isEmpty() || !this.isFullReason((Component)reason.get())) {
            return;
        }
        Player player = event.getPlayer();
        int baseScore = this.isVip(player) ? 500 : 100;
        this.queueService.enqueue(event.getServer(), player, baseScore);
        int position = this.queueService.position(event.getServer(), player);
        int releaseRate = Math.max(1, this.releaseRate());
        long eta = this.queueService.estimateWaitSeconds(
            event.getServer(), player, releaseRate, this.config.checkIntervalMillis());
        event.setResult((KickedFromServerEvent.ServerKickResult)KickedFromServerEvent.Notify.create(
            Component.text(this.config.queueMessage() + " 优先级位置 #" + position + "，预计 " + eta + " 秒")));
    }

    void onServerConnected(ServerConnectedEvent event) {
        this.queueService.removeFromQueue(event.getServer(), event.getPlayer());
    }

    private void sampleAndProcess() {
        this.sampleMetrics();
        this.processQueues();
    }

    private void sampleMetrics() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        int memPercent = (int)(used * 100L / rt.maxMemory());
        int playerCount = this.plugin.proxy().getPlayerCount();
        int estimatedTps = Math.max(5, 20 - playerCount / 20);
        this.rateLimiter.updateTps(estimatedTps);
        this.rateLimiter.updateMemoryPercent(memPercent);
    }

    void processQueues() {
        ProxyServer proxy = this.plugin.proxy();
        int maxRelease = this.releaseRate();
        if (maxRelease <= 0) {
            return;
        }
        this.queueService.processQueues((player, serverName) -> {
            Optional<String> selected = this.targetPolicy.resolve(
                serverName, this.queueService.snapshot());
            if (selected.isEmpty()) {
                return false;
            }
            String selectedName = selected.get();
            RegisteredServer server = proxy.getServer(selectedName).orElse(null);
            if (server == null) {
                return false;
            }
            try {
                return (Boolean)((CompletableFuture)((CompletableFuture)player.createConnectionRequest(server)
                    .connect().thenApply(result -> result.isSuccessful()))
                    .orTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> false)).join();
            }
            catch (Exception e) {
                return false;
            }
        }, maxRelease);
    }

    private int releaseRate() {
        return this.releaseRate(this.rateLimiter.evaluateLoad());
    }

    private int releaseRate(AdaptiveRateLimiter.LoadLevel load) {
        switch (load) {
            case LOW: {
                return 5;
            }
            case NORMAL: {
                return 3;
            }
            case MODERATE: {
                return 2;
            }
            case HIGH: {
                return 1;
            }
            case CRITICAL: {
                return 0;
            }
        }
        return 3;
    }

    private boolean isVip(Player player) {
        return player.hasPermission("starx.vip");
    }

    private boolean isFullReason(Component reason) {
        String text = PlainTextComponentSerializer.plainText().serialize(reason).toLowerCase(Locale.ROOT);
        return this.config.fullPatterns().stream().anyMatch(text::contains);
    }

    AdaptiveRateLimiter.LoadLevel getCurrentLoadLevel() {
        return this.rateLimiter.evaluateLoad();
    }

    int getReleaseRate() {
        return this.releaseRate();
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
                    return "Server is full, you are queued. VIP players get priority.";
                }

                @Override
                public long checkIntervalMillis() {
                    return 3000L;
                }
            };
        }
    }

    private final class SmartQueueListener {
        private SmartQueueListener() {
        }

        @Subscribe
        public void onPostLogin(PostLoginEvent event) {
            SmartQueueModule.this.onLogin(event.getPlayer());
        }

        @Subscribe
        public void onKicked(KickedFromServerEvent event) {
            SmartQueueModule.this.onKicked(event);
        }

        @Subscribe
        public void onServerConnected(ServerConnectedEvent event) {
            SmartQueueModule.this.onServerConnected(event);
        }

        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            SmartQueueModule.this.onDisconnect(event);
        }
    }
}
