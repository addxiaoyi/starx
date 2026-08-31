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
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import io.github.addxiaoyi.starx.common.skin.NoopSkinRepository;
import io.github.addxiaoyi.starx.common.skin.SkinService;
import io.github.addxiaoyi.starx.common.skin.SkinsRestorerSkinRepository;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.bridge.VelocityBackendBridge;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.website.WebsiteSyncConfig;
import io.github.addxiaoyi.starx.website.WebsiteSyncHttpClient;
import io.github.addxiaoyi.starx.website.WebsiteSyncApiException;
import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;

public final class SkinBridgeModule
implements VelocityModule {
    private static final Logger LOGGER = Logger.getLogger(SkinBridgeModule.class.getName());
    private static final long REFRESH_DEDUPLICATION_MS = 5000L;
    private static final int MAX_RECENT_REFRESHES = 4096;
    private final StarxVelocityPlugin plugin;
    private final ProxyServer proxy;
    private final EventBus eventBus;
    private final Supplier<SkinRepository> repositoryFactory;
    private final String skinProfileBaseUrl;
    private final WebsiteSyncConfig websiteSyncConfig;
    private final WebsiteSyncHttpClient websiteCommandClient;
    private SkinService skinService;
    private SkinRepository writableSkinRepository;
    private WebsiteSkinRepository websiteRepository;
    private volatile boolean skinsRestorerAvailable;
    private final VelocityBackendBridge backendBridge;
    private final BackendSkinFallbackCache backendSkinCache;
    private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;
    private final ConcurrentMap<UUID, Long> recentSkinRefreshes = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> appliedSkinProviders = new ConcurrentHashMap<>();
    private Listener listener;
    private CommandMeta skinCommandMeta;

    public SkinBridgeModule(ProxyServer proxy, EventBus eventBus) {
        this.plugin = null;
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.skinProfileBaseUrl = null;
        this.websiteSyncConfig = null;
        this.websiteCommandClient = null;
        this.repositoryFactory = null;
        this.backendBridge = null;
        this.backendSkinCache = null;
        this.knownMinecraftUuidsResolver = uuid -> Set.of(uuid);
    }

    public SkinBridgeModule(
        StarxVelocityPlugin plugin,
        EventBus eventBus,
        VelocityBackendBridge backendBridge
    ) {
        this(plugin, eventBus, backendBridge, uuid -> Set.of(uuid));
    }

    public SkinBridgeModule(
        StarxVelocityPlugin plugin,
        EventBus eventBus,
        VelocityBackendBridge backendBridge,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxy = plugin.proxy();
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.backendBridge = Objects.requireNonNull(backendBridge, "backendBridge");
        WebsiteSyncConfig websiteSync = plugin.config().websiteSync();
        this.websiteSyncConfig = websiteSync;
        this.websiteCommandClient = websiteSync.enabled() ? new WebsiteSyncHttpClient(websiteSync) : null;
        this.skinProfileBaseUrl = websiteSync.enabled()
            ? websiteSync.siteUrl().resolve("/api/public/skin-profile").toString()
            : null;
        this.repositoryFactory = null;
        this.backendSkinCache = new BackendSkinFallbackCache(
            plugin.dataDirectory().resolve("cache").resolve("backend-skins"),
            Duration.ofHours(24),
            LOGGER);
        this.knownMinecraftUuidsResolver = Objects.requireNonNull(
            knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
    }

    public SkinBridgeModule(ProxyServer proxy, EventBus eventBus, String skinProfileBaseUrl) {
        this.plugin = null;
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.skinProfileBaseUrl = skinProfileBaseUrl;
        this.websiteSyncConfig = null;
        this.websiteCommandClient = null;
        this.repositoryFactory = null;
        this.backendBridge = null;
        this.backendSkinCache = null;
        this.knownMinecraftUuidsResolver = uuid -> Set.of(uuid);
    }

    SkinBridgeModule(ProxyServer proxy, EventBus eventBus, Supplier<SkinRepository> repositoryFactory) {
        this.plugin = null;
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.repositoryFactory = Objects.requireNonNull(repositoryFactory, "repositoryFactory");
        this.skinProfileBaseUrl = null;
        this.websiteSyncConfig = null;
        this.websiteCommandClient = null;
        this.backendBridge = null;
        this.backendSkinCache = null;
        this.knownMinecraftUuidsResolver = uuid -> Set.of(uuid);
    }

    @Override
    public String name() {
        return "starx.skin-bridge";
    }

    @Override
    public void onEnable() {
        SkinRepository repository;
        if (this.repositoryFactory != null) {
            repository = Objects.requireNonNull(
                this.repositoryFactory.get(), "repositoryFactory returned null");
        } else if (this.plugin == null && this.isWebsiteSkinAvailable()) {
            repository = new NoopSkinRepository();
        } else {
            boolean pluginInstalled = this.proxy.getPluginManager()
                .getPlugin("skinsrestorer").isPresent();
            repository = pluginInstalled ? new SkinsRestorerSkinRepository() : new NoopSkinRepository();
        }
        this.skinsRestorerAvailable = repository.isAvailable();
        this.writableSkinRepository = repository;
        if (this.isWebsiteSkinAvailable()) {
            this.websiteRepository = new WebsiteSkinRepository(
                this.skinProfileBaseUrl,
                Logger.getLogger(WebsiteSkinRepository.class.getName()));
        }
        this.skinService = new SkinService(
            repository, this.eventBus, this.knownMinecraftUuidsResolver);
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
        this.writableSkinRepository = null;
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
        String playerName = this.proxy.getPlayer(uuid)
            .map(Player::getUsername)
            .orElse(uuid.toString());
        this.refreshSkin(uuid, playerName);
    }

    public void refreshSkin(UUID uuid, String playerName) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(playerName, "playerName");
        Optional<Player> player = this.proxy.getPlayer(uuid);
        if (player.isPresent()) {
            this.refreshSkin(player.get(), null);
            return;
        }
        if (this.skinService == null) {
            return;
        }
        if (this.websiteRepository != null && this.applyWebsiteSkin(uuid, playerName)) {
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
        if (this.websiteRepository != null
            && this.applyWebsiteSkin(player.getUniqueId(), player.getUsername())) {
            return;
        }
        if (hasExistingTexture(player.getGameProfileProperties())) {
            this.appliedSkinProviders.putIfAbsent(player.getUniqueId(), "登录档案皮肤");
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
            this.appliedSkinProviders.put(skin.uuid(), cached
                ? skin.provider() + " (缓存)"
                : skin.provider());
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
        if (this.websiteRepository == null) {
            return false;
        }
        return this.applyWebsiteSkin(uuid, playerName, true);
    }

    private boolean applyWebsiteSkin(UUID uuid, String playerName) {
        return this.applyWebsiteSkin(uuid, playerName, false);
    }

    private boolean applyWebsiteSkin(UUID uuid, String playerName, boolean forceRefresh) {
        Optional<WebsiteSkinProfile> profile = this.websiteRepository.findProfile(playerName, forceRefresh);
        if (profile.isEmpty()) {
            LOGGER.fine("Website skin profile not found for " + playerName);
            return false;
        }
        WebsiteSkinProfile websiteProfile = profile.get();
        if (!websiteProfile.belongsTo(uuid, playerName)) {
            LOGGER.warning("Ignoring website skin profile with mismatched identity for " + playerName);
            return false;
        }
        return this.deliverSkin(uuid, playerName, websiteProfile, "website", "网站绑定皮肤");
    }

    private boolean deliverSkin(
        UUID uuid,
        String playerName,
        WebsiteSkinProfile profile,
        String provider,
        String providerLabel
    ) {
        String value = profile.textureValue(uuid, playerName);
        boolean locallyPersisted = false;
        SkinRepository writable = this.writableSkinRepository;
        if (writable != null) {
            try {
                locallyPersisted = writable.trySetSkinData(uuid, value, "");
                if (!locallyPersisted) {
                    LOGGER.warning("Skin repository is unavailable for " + playerName);
                }
            } catch (RuntimeException error) {
                LOGGER.warning("Skin could not be persisted locally for " + playerName
                    + ": " + error.getClass().getSimpleName());
            }
        }
        int backendTargets = 0;
        if (this.backendBridge != null) {
            try {
                backendTargets = this.backendBridge.broadcastSkinUpdate(
                    uuid, playerName, value, "").size();
            } catch (RuntimeException error) {
                LOGGER.warning("Skin could not be broadcast for " + playerName
                    + ": " + error.getClass().getSimpleName());
            }
        }
        Optional<Player> online = this.proxy.getPlayer(uuid);
        boolean appliedToOnlinePlayer = online.isPresent();
        if (appliedToOnlinePlayer) {
            Player player = online.get();
            player.setGameProfileProperties(profile.merge(
                uuid,
                playerName,
                player.getGameProfileProperties()));
            this.eventBus.publish("skin:applied", java.util.Map.of(
                "uuid", uuid.toString(), "provider", provider));
        }
        if (!isWebsiteSkinApplied(locallyPersisted, backendTargets, appliedToOnlinePlayer)) {
            LOGGER.warning("Skin was found but no delivery target accepted it for " + playerName);
            return false;
        }
        this.appliedSkinProviders.put(uuid, providerLabel);
        this.eventBus.publish("skin:updated", java.util.Map.of(
            "uuid", uuid.toString(),
            "provider", provider,
            "localPersisted", Boolean.toString(locallyPersisted),
            "backendTargets", Integer.toString(backendTargets)));
        return true;
    }

    private void registerSkinCommand() {
        this.skinCommandMeta = this.proxy.getCommandManager()
            .metaBuilder("sxskin")
            .build();
        this.proxy.getCommandManager().register(this.skinCommandMeta, (Command)new SkinCommand());
    }

    private boolean canApplyCatalogSkin() {
        return this.plugin != null
            && this.websiteCommandClient != null
            && this.websiteSyncConfig != null
            && this.websiteSyncConfig.hasNodeCredential();
    }

    private void applyCatalogSkin(Player player, String catalogId) {
        if (!this.canApplyCatalogSkin()) {
            player.sendMessage(Component.text("网站皮肤同步尚未完成节点授权，暂时无法在游戏内应用皮肤。"));
            return;
        }
        if (!catalogId.matches("[A-Za-z0-9._-]{1,96}")) {
            player.sendMessage(Component.text("皮肤编号格式无效。"));
            return;
        }
        player.sendMessage(Component.text("正在应用网站皮肤……"));
        this.plugin.proxy().getScheduler().buildTask(this.plugin, () -> {
            try {
                this.websiteCommandClient.applyCatalogSkin(
                    this.websiteSyncConfig.nodeToken(),
                    catalogId,
                    player.getUniqueId(),
                    player.getUsername());
                boolean refreshed = this.refreshSkinFromWebsite(
                    player.getUniqueId(), player.getUsername());
                if (refreshed) {
                    player.sendMessage(Component.text("皮肤已应用并同步到当前服务器。"));
                } else {
                    player.sendMessage(Component.text("皮肤已保存；同步将在下次进服时重试。"));
                }
            } catch (WebsiteSyncApiException error) {
                LOGGER.warning("Website catalog skin apply failed for " + player.getUsername()
                    + ": " + error.errorCode());
                player.sendMessage(Component.text("网站未接受该皮肤：" + error.errorCode()));
            } catch (RuntimeException error) {
                LOGGER.warning("Website catalog skin apply failed for " + player.getUsername()
                    + ": " + error.getClass().getSimpleName());
                player.sendMessage(Component.text("皮肤应用失败，请稍后重试。"));
            }
        }).schedule();
    }

    private void applyExternalSkin(Player player, String skinUrl) {
        Optional<WebsiteSkinProfile> profile = WebsiteSkinProfile.externalSkin(
            player.getUniqueId(), player.getUsername(), skinUrl);
        if (profile.isEmpty()) {
            player.sendMessage(Component.text("外部皮肤地址无效：仅允许 Minecraft 官方纹理地址。"));
            return;
        }
        this.plugin.proxy().getScheduler().buildTask(this.plugin, () -> {
            boolean applied = this.deliverSkin(
                player.getUniqueId(), player.getUsername(), profile.get(),
                "external-url", "外部皮肤站");
            player.sendMessage(Component.text(applied
                ? "外部皮肤已应用并同步。"
                : "外部皮肤无法持久化，将在下次刷新时重试。"));
        }).schedule();
    }

    private void refreshSkinAsync(Player player, RegisteredServer connectedServer) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long previous = this.recentSkinRefreshes.put(uuid, now);
        if (previous != null && now - previous < REFRESH_DEDUPLICATION_MS) {
            return;
        }
        if (this.recentSkinRefreshes.size() > MAX_RECENT_REFRESHES) {
            this.recentSkinRefreshes.entrySet().removeIf(entry ->
                now - entry.getValue() >= REFRESH_DEDUPLICATION_MS);
        }
        this.plugin.proxy().getScheduler().buildTask(this.plugin,
            () -> this.refreshSkin(player, connectedServer)).schedule();
    }

    private void sendSkinStatus(CommandSource source, Player player) {
        boolean hasTextures = player.getGameProfileProperties().stream()
            .anyMatch(property -> property.getName().equals("textures"));
        String website = this.websiteRepository == null ? "关闭" : "启用（缓存 5 分钟）";
        String skinsRestorer = this.skinsRestorerAvailable ? "可用" : "不可用";
        String bridge = this.backendBridge == null ? "关闭" : "启用";
        String provider = this.appliedSkinProviders.getOrDefault(player.getUniqueId(), "尚未确认");
        source.sendMessage(Component.text("皮肤诊断：" + player.getUsername()
            + " UUID=" + player.getUniqueId()));
        source.sendMessage(Component.text("网站档案=" + website
            + "，SkinsRestorer=" + skinsRestorer + "，后端桥接=" + bridge));
        source.sendMessage(Component.text("当前纹理属性=" + (hasTextures ? "已设置" : "未设置")
            + "，最近确认来源=" + provider));
    }

    static boolean shouldRefreshAfterConnect(
        boolean backendBridgeAvailable,
        boolean proxyProviderAvailable,
        boolean websiteProviderAvailable
    ) {
        return backendBridgeAvailable || proxyProviderAvailable || websiteProviderAvailable;
    }

    static boolean isWebsiteSkinApplied(
        boolean locallyPersisted,
        int backendTargets,
        boolean appliedToOnlinePlayer
    ) {
        if (backendTargets < 0) {
            throw new IllegalArgumentException("backendTargets must not be negative");
        }
        return locallyPersisted || backendTargets > 0;
    }

    static boolean hasExistingTexture(List<GameProfile.Property> properties) {
        if (properties == null) {
            return false;
        }
        for (GameProfile.Property property : properties) {
            if (!property.getName().equals("textures") || property.getValue().isBlank()) {
                continue;
            }
            try {
                String decoded = new String(
                    Base64.getDecoder().decode(property.getValue()), StandardCharsets.UTF_8);
                JsonElement root = JsonParser.parseString(decoded);
                JsonElement textures = root.isJsonObject()
                    ? root.getAsJsonObject().get("textures") : null;
                JsonElement skin = textures != null && textures.isJsonObject()
                    ? textures.getAsJsonObject().get("SKIN") : null;
                if (skin != null && skin.isJsonObject() && isHttpsTextureUrl(skin.getAsJsonObject())) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed profile properties and continue to the configured fallback.
            }
        }
        return false;
    }

    private static boolean isHttpsTextureUrl(JsonObject skin) {
        JsonElement url = skin.get("url");
        if (url == null || !url.isJsonPrimitive() || !url.getAsJsonPrimitive().isString()) {
            return false;
        }
        try {
            URI uri = URI.create(url.getAsString());
            return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && !uri.getHost().isBlank()
                && uri.getUserInfo() == null
                && uri.getFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private final class Listener {
        @Subscribe
        public void onPostLogin(PostLoginEvent event) {
            Player player = event.getPlayer();
            SkinBridgeModule.this.refreshSkinAsync(player, null);
        }

        @Subscribe
        public void onServerConnected(ServerConnectedEvent event) {
            if (!shouldRefreshAfterConnect(
                SkinBridgeModule.this.backendBridge != null,
                SkinBridgeModule.this.skinsRestorerAvailable,
                SkinBridgeModule.this.isWebsiteSkinAvailable())) {
                return;
            }
            SkinBridgeModule.this.refreshSkinAsync(event.getPlayer(), event.getServer());
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
                String[] arguments = invocation.arguments();
                if (arguments.length > 0) {
                    if (arguments.length == 2 && "apply".equalsIgnoreCase(arguments[0])) {
                        SkinBridgeModule.this.applyCatalogSkin(player, arguments[1]);
                        return;
                    }
                    if (arguments.length == 1 && "status".equalsIgnoreCase(arguments[0])) {
                        SkinBridgeModule.this.sendSkinStatus(source, player);
                        return;
                    }
                    if (arguments.length == 2 && "url".equalsIgnoreCase(arguments[0])) {
                        if (!source.hasPermission("starx.skin.external-url")) {
                            source.sendMessage(Component.text("你没有使用外部皮肤地址的权限。"));
                            return;
                        }
                        SkinBridgeModule.this.applyExternalSkin(player, arguments[1]);
                        return;
                    }
                    source.sendMessage(Component.text(
                        "用法：/sxskin、/sxskin status、/sxskin apply <皮肤编号> 或 /sxskin url <HTTPS纹理地址>"));
                    return;
                }
                if (SkinBridgeModule.this.skinProfileBaseUrl != null && !SkinBridgeModule.this.skinProfileBaseUrl.isBlank()) {
                    source.sendMessage((Component)Component.text((String)("正在刷新 " + player.getUsername()
                        + " 的皮肤；未绑定网站角色时将回退正版皮肤……")));
                    SkinBridgeModule.this.refreshSkinAsync(player, null);
                    source.sendMessage((Component)Component.text((String)"已提交皮肤刷新请求。"));
                } else {
                    source.sendMessage((Component)Component.text((String)("正在刷新 " + player.getUsername() + " 的皮肤……")));
                    SkinBridgeModule.this.refreshSkinAsync(player, null);
                    source.sendMessage((Component)Component.text((String)"已提交皮肤刷新请求。"));
                }
            }
        }
    }
}
