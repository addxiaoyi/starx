/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.command.Command
 *  com.velocitypowered.api.command.CommandSource
 *  com.velocitypowered.api.command.SimpleCommand
 *  com.velocitypowered.api.command.SimpleCommand$Invocation
 *  com.velocitypowered.api.proxy.Player
 *  com.velocitypowered.api.proxy.ProxyServer
 *  net.kyori.adventure.text.Component
 */
package io.github.addxiaoyi.starx.velocity.module.skin;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import io.github.addxiaoyi.starx.common.skin.NoopSkinRepository;
import io.github.addxiaoyi.starx.common.skin.SkinService;
import io.github.addxiaoyi.starx.common.skin.SkinsRestorerSkinRepository;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.bridge.VelocityBackendBridge;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;

public final class SkinBridgeModule
implements VelocityModule {
    private static final Logger LOGGER = Logger.getLogger(SkinBridgeModule.class.getName());
    private final StarxVelocityPlugin plugin;
    private final ProxyServer proxy;
    private final EventBus eventBus;
    private final Supplier<SkinRepository> repositoryFactory;
    private final String skinProfileBaseUrl;
    private SkinService skinService;
    private WebsiteSkinRepository websiteRepository;
    private volatile boolean skinsRestorerAvailable;
    private final VelocityBackendBridge backendBridge;
    private final BackendSkinFallbackCache backendSkinCache;
    private Listener listener;
    private CommandMeta skinCommandMeta;

    public SkinBridgeModule(ProxyServer proxy, EventBus eventBus) {
        this.plugin = null;
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.skinProfileBaseUrl = null;
        this.repositoryFactory = null;
        this.backendBridge = null;
        this.backendSkinCache = null;
    }

    public SkinBridgeModule(
        StarxVelocityPlugin plugin,
        EventBus eventBus,
        VelocityBackendBridge backendBridge
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxy = plugin.proxy();
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.backendBridge = Objects.requireNonNull(backendBridge, "backendBridge");
        this.skinProfileBaseUrl = null;
        this.repositoryFactory = null;
        this.backendSkinCache = new BackendSkinFallbackCache(
            plugin.dataDirectory().resolve("cache").resolve("backend-skins"),
            Duration.ofHours(24),
            LOGGER);
    }

    public SkinBridgeModule(ProxyServer proxy, EventBus eventBus, String skinProfileBaseUrl) {
        this.plugin = null;
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.skinProfileBaseUrl = skinProfileBaseUrl;
        this.repositoryFactory = null;
        this.backendBridge = null;
        this.backendSkinCache = null;
    }

    SkinBridgeModule(ProxyServer proxy, EventBus eventBus, Supplier<SkinRepository> repositoryFactory) {
        this.plugin = null;
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.repositoryFactory = Objects.requireNonNull(repositoryFactory, "repositoryFactory");
        this.skinProfileBaseUrl = null;
        this.backendBridge = null;
        this.backendSkinCache = null;
    }

    @Override
    public String name() {
        return "starx.skin-bridge";
    }

    @Override
    public void onEnable() {
        SkinRepository repository;
        if (this.skinProfileBaseUrl != null && !this.skinProfileBaseUrl.isBlank()) {
            this.websiteRepository = new WebsiteSkinRepository(this.skinProfileBaseUrl, Logger.getLogger(WebsiteSkinRepository.class.getName()));
            repository = this.websiteRepository;
            this.skinsRestorerAvailable = false;
        } else if (this.repositoryFactory != null) {
            repository = this.repositoryFactory.get();
            this.skinsRestorerAvailable = this.proxy.getPluginManager().getPlugin("skinsrestorer").isPresent();
        } else {
            this.skinsRestorerAvailable = this.proxy.getPluginManager().getPlugin("skinsrestorer").isPresent();
            repository = this.skinsRestorerAvailable ? new SkinsRestorerSkinRepository() : new NoopSkinRepository();
        }
        this.skinService = new SkinService(repository, this.eventBus);
        if (this.backendBridge != null) {
            this.backendBridge.onSkinResponse(this::applyBackendSkin);
            this.backendBridge.onBackendReady(this::refreshSkin);
        }
        if (this.plugin != null) {
            this.listener = new Listener();
            this.proxy.getEventManager().register(this.plugin, this.listener);
        }
        this.registerSkinCommand();
    }

    @Override
    public void onDisable() {
        Listener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) {
            this.proxy.getEventManager().unregisterListener(this.plugin, currentListener);
        }
        CommandMeta currentCommand = this.skinCommandMeta;
        this.skinCommandMeta = null;
        if (currentCommand != null) {
            this.proxy.getCommandManager().unregister(currentCommand);
        }
        if (this.backendBridge != null) {
            this.backendBridge.onSkinResponse(message -> { });
            this.backendBridge.onBackendReady((player, server) -> { });
        }
        this.skinService = null;
        this.websiteRepository = null;
        this.skinsRestorerAvailable = false;
    }

    public boolean isSkinsRestorerAvailable() {
        return this.skinsRestorerAvailable;
    }

    public boolean isWebsiteSkinAvailable() {
        return this.skinProfileBaseUrl != null && !this.skinProfileBaseUrl.isBlank();
    }

    public void refreshSkin(UUID uuid) {
        this.refreshSkin(uuid, uuid.toString());
    }

    public void refreshSkin(UUID uuid, String playerName) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(playerName, "playerName");
        if (this.skinService == null) {
            return;
        }
        Optional<Player> player = this.proxy.getPlayer(uuid);
        if (player.isPresent()) {
            this.refreshSkin(player.get(), null);
            return;
        }
        if (this.backendBridge != null
            && !this.skinsRestorerAvailable
            && !this.isWebsiteSkinAvailable()
            && this.backendBridge.requestSkin(uuid, playerName).accepted()) {
            return;
        }
        this.skinService.refreshSkin(uuid, playerName);
    }

    private void refreshSkin(Player player, RegisteredServer connectedServer) {
        if (this.skinService == null) {
            return;
        }
        if (this.websiteRepository != null) {
            this.applyWebsiteSkin(player);
            return;
        }
        if (this.backendBridge != null
            && !this.skinsRestorerAvailable
            && !this.isWebsiteSkinAvailable()) {
            VelocityBackendBridge.DispatchResult dispatch = connectedServer == null
                ? this.backendBridge.requestSkin(player)
                : this.backendBridge.requestSkin(player, connectedServer);
            if (dispatch.accepted()) {
                return;
            }
            LOGGER.warning("Backend skin request unavailable for " + player.getUsername()
                + ": " + dispatch);
            if (this.applyCachedBackendSkin(player)) {
                return;
            }
        }
        this.skinService.refreshSkin(player.getUniqueId(), player.getUsername());
    }

    private void applyBackendSkin(io.github.addxiaoyi.starx.api.bridge.BridgeMessage message) {
        Optional<BackendSkinData> response = BackendSkinData.from(message);
        if (response.isEmpty()) {
            LOGGER.info("Backend skin response found=false correlation=" + message.correlationId());
            try {
                UUID uuid = UUID.fromString(message.attributes().getOrDefault("uuid", ""));
                this.proxy.getPlayer(uuid).ifPresent(this::applyCachedBackendSkin);
            } catch (IllegalArgumentException ignored) {
                LOGGER.fine("Backend skin response omitted a valid UUID for cache fallback");
            }
            return;
        }
        BackendSkinData skin = response.get();
        if (this.backendSkinCache != null) {
            this.backendSkinCache.put(skin, Instant.now());
        }
        LOGGER.info(backendSkinResponseMessage(
            skin.uuid(), skin.provider(), skin.value().length(), true));
        this.applyBackendSkinData(skin, false);
    }

    private boolean applyCachedBackendSkin(Player player) {
        if (this.backendSkinCache == null) return false;
        Optional<BackendSkinData> cached = this.backendSkinCache.find(
            player.getUniqueId(), player.getUsername(), Instant.now());
        cached.ifPresent(skin -> this.applyBackendSkinData(skin, true));
        return cached.isPresent();
    }

    private void applyBackendSkinData(BackendSkinData skin, boolean cached) {
        this.proxy.getPlayer(skin.uuid()).ifPresent(player -> {
            player.setGameProfileProperties(skin.merge(player.getGameProfileProperties()));
            LOGGER.info(backendSkinAppliedMessage(
                skin.uuid(), cached ? skin.provider() + "-cache" : skin.provider()));
            this.eventBus.publish("skin:updated", java.util.Map.of(
                "uuid", skin.uuid().toString(), "provider", skin.provider(),
                "cached", Boolean.toString(cached)));
            this.eventBus.publish("skin:applied", java.util.Map.of(
                "uuid", skin.uuid().toString(), "provider", skin.provider(),
                "cached", Boolean.toString(cached)));
        });
    }

    static String backendSkinAppliedMessage(UUID uuid, String provider) {
        return "Applied backend skin uuid=" + Objects.requireNonNull(uuid, "uuid")
            + " provider=" + Objects.requireNonNull(provider, "provider");
    }

    static String backendSkinResponseMessage(
        UUID uuid,
        String provider,
        int valueLength,
        boolean found
    ) {
        if (valueLength < 0) {
            throw new IllegalArgumentException("valueLength must not be negative");
        }
        return "Received backend skin uuid=" + Objects.requireNonNull(uuid, "uuid")
            + " provider=" + Objects.requireNonNull(provider, "provider")
            + " found=" + found
            + " valueLength=" + valueLength;
    }

    public boolean refreshSkinFromWebsite(UUID uuid, String playerName) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(playerName, "playerName");
        if (this.skinProfileBaseUrl == null || this.skinProfileBaseUrl.isBlank()) {
            LOGGER.warning("Website skin base URL is not configured, cannot refresh from website.");
            return false;
        }
        Optional<Player> player = this.proxy.getPlayer(uuid);
        if (this.websiteRepository == null || player.isEmpty()) {
            return false;
        }
        return this.applyWebsiteSkin(player.get());
    }

    private boolean applyWebsiteSkin(Player player) {
        Optional<WebsiteSkinProfile> profile = this.websiteRepository.findProfile(player.getUsername());
        if (profile.isEmpty()) {
            LOGGER.warning("Website skin profile not found for " + player.getUsername());
            return false;
        }
        WebsiteSkinProfile websiteProfile = profile.get();
            player.setGameProfileProperties(websiteProfile.merge(
                player.getUniqueId(),
                player.getUsername(),
                player.getGameProfileProperties()));
            this.eventBus.publish("skin:updated", java.util.Map.of(
                "uuid", player.getUniqueId().toString(), "provider", "website"));
            this.eventBus.publish("skin:applied", java.util.Map.of(
                "uuid", player.getUniqueId().toString(), "provider", "website"));
        return true;
    }

    private void registerSkinCommand() {
        this.skinCommandMeta = this.proxy.getCommandManager()
            .metaBuilder("sxskin")
            .build();
        this.proxy.getCommandManager().register(this.skinCommandMeta, (Command)new SkinCommand());
    }

    static boolean shouldRefreshAfterConnect(
        boolean backendBridgeAvailable,
        boolean proxyProviderAvailable,
        boolean websiteProviderAvailable
    ) {
        return backendBridgeAvailable || proxyProviderAvailable || websiteProviderAvailable;
    }

    private final class Listener {
        @Subscribe
        public void onServerConnected(ServerConnectedEvent event) {
            if (!shouldRefreshAfterConnect(
                SkinBridgeModule.this.backendBridge != null,
                SkinBridgeModule.this.skinsRestorerAvailable,
                SkinBridgeModule.this.isWebsiteSkinAvailable())) {
                return;
            }
            SkinBridgeModule.this.refreshSkin(event.getPlayer(), event.getServer());
        }
    }

    private final class SkinCommand
    implements SimpleCommand {
        private SkinCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            CommandSource source = invocation.source();
            if (source instanceof Player) {
                Player player = (Player)source;
                if (SkinBridgeModule.this.skinProfileBaseUrl != null && !SkinBridgeModule.this.skinProfileBaseUrl.isBlank()) {
                    source.sendMessage((Component)Component.text((String)("正在从网站获取 " + player.getUsername() + " 的皮肤……")));
                    SkinBridgeModule.this.refreshSkinFromWebsite(player.getUniqueId(), player.getUsername());
                    source.sendMessage((Component)Component.text((String)"已提交网站皮肤刷新请求。"));
                } else {
                    source.sendMessage((Component)Component.text((String)("正在刷新 " + player.getUsername() + " 的皮肤……")));
                    SkinBridgeModule.this.skinService.refreshSkin(player.getUniqueId(), player.getUsername());
                    source.sendMessage((Component)Component.text((String)"已提交皮肤刷新请求。"));
                }
            }
        }
    }
}
