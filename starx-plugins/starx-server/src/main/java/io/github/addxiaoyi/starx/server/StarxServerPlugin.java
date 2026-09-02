package io.github.addxiaoyi.starx.server;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.compat.CompatibilityReport;
import io.github.addxiaoyi.starx.api.extension.StarxCapabilities;
import io.github.addxiaoyi.starx.api.extension.StarxService;
import io.github.addxiaoyi.starx.api.extension.StarxServiceEventTypes;
import io.github.addxiaoyi.starx.api.extension.StarxServiceProvider;
import io.github.addxiaoyi.starx.runtime.extension.DefaultStarxService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class StarxServerPlugin extends JavaPlugin implements StarxServiceProvider {
  private static final String NODE_ID_PATTERN = "[A-Za-z0-9_.-]{1,64}";

  private final long startedAt = System.currentTimeMillis();
  private final BackendSkinResolverRegistry skinResolvers = new BackendSkinResolverRegistry();
  private BukkitBackendBridge bridge;
  private BackendBridgeSession session;
  private StarxPlaceholderExpansion placeholders;
  private AccountAnvilController accountController;
  private volatile SkinsRestorerBackendSkinResolver skinResolver;
  private volatile BackendHeartbeatClient heartbeatClient;
  private volatile URI heartbeatVelocityUrl;
  private volatile String heartbeatApiKey;
  private volatile String heartbeatNodeId;
  private volatile Duration heartbeatTimeout;
  private ScheduledTask heartbeatTask;
  private final AtomicBoolean heartbeatDegraded = new AtomicBoolean();
  private volatile long lastPullbackMillis = 0;
  private static final long MIN_PULLBACK_INTERVAL_MS = 500;
  private final AtomicBoolean heartbeatEndpointRefreshScheduled = new AtomicBoolean();
  private volatile boolean heartbeatEndpointRefreshEnabled;
  private BackendMaintenanceState maintenance;
  private String serverType;
  private DefaultStarxService extensionService;
  private CompatibilityReport compatibilityReport;
  private BackendTabTitleListener tabTitleListener;

  @Override
  public void onEnable() {
    File configFile = new File(this.getDataFolder(), "config.yml");
    boolean firstBoot = !configFile.isFile();
    this.saveDefaultConfig();
    try {
      BackendConfigSchemaUpgrader.upgrade(this, firstBoot);
      BackendAutoConfigurator.apply(this, firstBoot);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("StarX backend configuration initialization failed", error);
    }
    String nodeId = Objects.requireNonNullElse(
        this.getConfig().getString("node-id"), "backend").trim();
    if (!nodeId.matches(NODE_ID_PATTERN)) {
      throw new IllegalStateException(
          "node-id must match " + NODE_ID_PATTERN + ", actual=" + nodeId);
    }
    this.serverType = Objects.requireNonNullElse(
        this.getConfig().getString("server-type"), inferServerType(nodeId)).trim();
    if (!this.serverType.matches(NODE_ID_PATTERN)) {
      throw new IllegalStateException(
          "server-type must match " + NODE_ID_PATTERN + ", actual=" + this.serverType);
    }

    ServerPlatform platform = ServerPlatform.detect();
    try {
      this.compatibilityReport = ServerCompatibility.evaluate(this, platform);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("StarX compatibility report failed", error);
    }
    this.maintenance = new BackendMaintenanceState(
        this.getConfig().getBoolean("network.maintenance", false),
        this::persistMaintenance);
    this.refreshSkinsRestorer();
    this.session = new BackendBridgeSession(
        nodeId,
        platform,
        this::readStatus,
        this.skinResolvers,
        this::applyMaintenance,
        Clock.systemUTC());
    if (this.getConfig().getBoolean("bridge.enabled", true)) {
      this.bridge = new BukkitBackendBridge(this, this.session);
      this.bridge.enable();
    }
    this.enableHeartbeat(nodeId);

    PluginCommand command = Objects.requireNonNull(
        this.getCommand("starxserver"),
        "starxserver command is missing from plugin.yml");
    command.setExecutor(new StarxServerCommand(this.session, this.compatibilityReport));
    String accountApiKey = this.getConfig().getString("bridge.heartbeat.api-key", "");
    StarxAccountClient accountClient = accountApiKey == null || accountApiKey.isBlank()
        ? null
        : new StarxAccountClient(
            this.getConfig().getString(
                "bridge.heartbeat.velocity-url", "http://127.0.0.1:8788"),
            accountApiKey);
    this.accountController = new AccountAnvilController(this, accountClient);
    PluginCommand accountCommand = Objects.requireNonNull(
        this.getCommand("starxaccount"),
        "starxaccount command is missing from plugin.yml");
    accountCommand.setExecutor(this.accountController);
    this.getServer().getPluginManager().registerEvents(this.accountController, this);
    this.getServer().getPluginManager().registerEvents(new IntegrationListener(), this);
    this.enablePlaceholderApi();
    this.tabTitleListener = new BackendTabTitleListener();
    this.getServer().getPluginManager().registerEvents(this.tabTitleListener, this);
    this.getServer().getGlobalRegionScheduler().runAtFixedRate(
        this, task -> this.tabTitleListener.applyAll(), 40L, 100L);
    this.installExtensionService(platform);
    this.getLogger().info(
        "StarX backend ready: node=" + nodeId
            + " platform=" + platform
            + " bridge=" + (this.bridge != null));
  }

  @Override
  public StarxService starxService() {
    DefaultStarxService service = this.extensionService;
    if (service == null) {
      throw new IllegalStateException("StarX extension service is not initialized");
    }
    return service;
  }

  @Override
  public void onDisable() {
    DefaultStarxService service = this.extensionService;
    this.extensionService = null;
    if (service != null) {
      try {
        service.publishSystemEvent(StarxServiceEventTypes.BACKEND_STOPPING, Map.of());
        this.getServer().getServicesManager().unregister(StarxService.class, service);
        service.close();
      } catch (RuntimeException error) {
        this.getLogger().log(Level.SEVERE, "Unable to stop StarX extension service", error);
      }
    }
    AccountAnvilController currentAccountController = this.accountController;
    this.accountController = null;
    if (currentAccountController != null) {
      currentAccountController.close();
    }
    ScheduledTask currentHeartbeat = this.heartbeatTask;
    this.heartbeatTask = null;
    if (currentHeartbeat != null) {
      currentHeartbeat.cancel();
    }
    this.heartbeatClient = null;
    BukkitBackendBridge current = this.bridge;
    this.bridge = null;
    if (current != null) {
      current.disable();
    }
    this.session = null;
    this.maintenance = null;
    this.serverType = null;
    this.compatibilityReport = null;
    this.skinResolver = null;
    this.skinResolvers.clear();
    StarxPlaceholderExpansion currentPlaceholders = this.placeholders;
    this.placeholders = null;
    if (currentPlaceholders != null) {
      currentPlaceholders.unregister();
    }
  }

  private void installExtensionService(ServerPlatform platform) {
    LinkedHashSet<String> capabilities = new LinkedHashSet<>(
        ServerCapabilities.forPlatform(platform));
    capabilities.add(StarxCapabilities.BACKEND_BRIDGE);
    capabilities.add(StarxCapabilities.BACKEND_STATUS);
    capabilities.add(StarxCapabilities.BACKEND_HEARTBEAT);
    capabilities.add(StarxCapabilities.BACKEND_SKIN);
    if (this.placeholders != null) {
      capabilities.add(StarxCapabilities.PLACEHOLDER_API);
    }
    SkinsRestorerBackendSkinResolver skins = this.skinResolver;
    if (skins != null && skins.available()) {
      capabilities.add(StarxCapabilities.SKINS_RESTORER);
    }
    DefaultStarxService service = new DefaultStarxService(
        this.getPluginMeta().getVersion(), platform.bridgeKind(), Set.copyOf(capabilities));
    this.extensionService = service;
    this.getServer().getServicesManager().register(
        StarxService.class, service, this, ServicePriority.Normal);
    service.publishSystemEvent(StarxServiceEventTypes.BACKEND_READY, this.readStatus());
  }

  private void enableHeartbeat(String nodeId) {
    BackendHeartbeatConfig config = BackendHeartbeatConfig.create(
        this.getConfig().getBoolean("bridge.heartbeat.enabled", false),
        this.getConfig().getString(
            "bridge.heartbeat.velocity-url", "http://127.0.0.1:8788"),
        this.getConfig().getString("bridge.heartbeat.api-key", ""),
        nodeId,
        this.getConfig().getInt("bridge.heartbeat.interval-seconds", 15),
        this.getConfig().getInt("bridge.heartbeat.timeout-ms", 4_000));
    if (!config.enabled()) {
      return;
    }

    this.heartbeatVelocityUrl = config.velocityUrl();
    this.heartbeatApiKey = config.apiKey();
    this.heartbeatNodeId = config.serverName();
    this.heartbeatTimeout = config.timeout();
    this.heartbeatEndpointRefreshEnabled =
        this.getConfig().getBoolean("auto-config.enabled", true)
            && this.getConfig().getBoolean("auto-config.discover-velocity", true)
            && this.getConfig().getBoolean("auto-config.manage-heartbeat", true);
    this.heartbeatClient = new BackendHeartbeatClient(
        config.velocityUrl(), config.apiKey(), config.serverName(), config.timeout());
    long periodTicks = Math.multiplyExact(config.interval().toSeconds(), 20L);
    this.heartbeatTask = this.getServer().getGlobalRegionScheduler().runAtFixedRate(
        this,
        ignored -> this.publishHeartbeat(),
        1L,
        periodTicks);
    this.getLogger().info(
        "StarX empty-server heartbeat ready: server=" + config.serverName()
            + " endpoint=" + config.velocityUrl().resolve("/v1/backend/heartbeat")
            + " interval=" + config.interval().toSeconds() + "s");
  }

  private void publishHeartbeat() {
    BackendHeartbeatClient client = this.heartbeatClient;
    BackendBridgeSession currentSession = this.session;
    if (client == null || currentSession == null) {
      return;
    }
    this.publishHeartbeatInternal(client, currentSession);
  }

  /**
   * 内部心跳调度：根据 mailbox 积压情况决定是否继续拉取命令。
   * 当前端有积压消息（queued > 0）时，立即触发下轮心跳，以实现「满缓冲区即推送」。
   */
  private void publishHeartbeatInternal(
      BackendHeartbeatClient client,
      BackendBridgeSession session
  ) {
    this.publishHeartbeatInternal(client, session, 0);
  }

  /**
   * 积压拉取循环：每轮交换后若 Velocity 端仍有积压则立即重入，
   * 但受最大连续轮数与最小间隔双重节流，防止积压状态异常时形成紧密空转。
   */
  private void publishHeartbeatInternal(
      BackendHeartbeatClient client,
      BackendBridgeSession session,
      int pullbackRound
  ) {
    // 单次触发最多连续拉取 8 轮；超过后放弃，等待下一个正常心跳周期。
    if (pullbackRound >= 8) {
      return;
    }
    long now = System.currentTimeMillis();
    long sinceLast = now - this.lastPullbackMillis;
    if (sinceLast < MIN_PULLBACK_INTERVAL_MS) {
      return;
    }
    this.lastPullbackMillis = now;
    client.sendWithBacklog(session.statusReport(UUID.randomUUID().toString()))
        .whenComplete((reply, error) -> {
          if (error != null) {
            this.handleHeartbeatError(error);
            return;
          }
          BackendHeartbeatExchange.run(
              client,
              session,
              session.statusReport(UUID.randomUUID().toString()),
              8,
              command -> {
                CompletableFuture<Optional<BridgeMessage>> scheduled = new CompletableFuture<>();
                this.getServer().getGlobalRegionScheduler().run(this, ignored -> {
                  try {
                    scheduled.complete(command.get());
                  } catch (RuntimeException e) {
                    scheduled.completeExceptionally(e);
                  }
                });
                return scheduled;
              })
              .whenComplete((ignored, exchangeError) -> {
                if (exchangeError == null) {
                  if (this.heartbeatDegraded.getAndSet(false)) {
                    this.getLogger().info("StarX empty-server heartbeat recovered");
                  }
                  // 仍有积压（至少 1 条）时，立即触发下轮心跳，实现实时推送
                  if (reply.hasCommand() && reply.queuedRemaining() > 0) {
                    this.publishHeartbeatInternal(client, session, pullbackRound + 1);
                  }
                  return;
                }
                this.handleHeartbeatError(exchangeError);
              });
        });
  }

  private void handleHeartbeatError(Throwable error) {
    this.requestHeartbeatEndpointRefresh();
    if (this.heartbeatDegraded.compareAndSet(false, true)) {
      this.getLogger().log(
          Level.WARNING,
          "StarX empty-server heartbeat failed; player-carried bridge remains available: {0}",
          heartbeatFailureMessage(error));
    }
  }

  private void requestHeartbeatEndpointRefresh() {
    if (!this.heartbeatEndpointRefreshEnabled
        || !this.heartbeatEndpointRefreshScheduled.compareAndSet(false, true)) {
      return;
    }
    try {
      this.getServer().getGlobalRegionScheduler().run(this, ignored -> {
        try {
          this.refreshHeartbeatEndpoint();
        } finally {
          this.heartbeatEndpointRefreshScheduled.set(false);
        }
      });
    } catch (RuntimeException schedulingFailure) {
      this.heartbeatEndpointRefreshScheduled.set(false);
    }
  }

  private void refreshHeartbeatEndpoint() {
    Optional<BackendAutoConfigurator.VelocityEndpoint> discovered;
    try {
      discovered = BackendAutoConfigurator.discoverVelocity(this);
    } catch (RuntimeException discoveryFailure) {
      this.getLogger().log(
          Level.FINE,
          "StarX runtime endpoint rediscovery failed",
          discoveryFailure);
      return;
    }
    if (discovered.isEmpty()) {
      return;
    }
    BackendAutoConfigurator.VelocityEndpoint endpoint = discovered.orElseThrow();
    URI velocityUrl;
    try {
      velocityUrl = URI.create(endpoint.baseUrl());
    } catch (IllegalArgumentException invalidEndpoint) {
      return;
    }
    String apiKey = endpoint.apiKey();
    if (velocityUrl.equals(this.heartbeatVelocityUrl)
        && apiKey.equals(this.heartbeatApiKey)) {
      return;
    }
    String nodeId = this.heartbeatNodeId;
    Duration timeout = this.heartbeatTimeout;
    if (nodeId == null || timeout == null) {
      return;
    }

    BackendHeartbeatClient replacement =
        new BackendHeartbeatClient(velocityUrl, apiKey, nodeId, timeout);
    this.heartbeatClient = replacement;
    this.heartbeatVelocityUrl = velocityUrl;
    this.heartbeatApiKey = apiKey;
    this.getConfig().set("bridge.heartbeat.velocity-url", endpoint.baseUrl());
    this.getConfig().set("bridge.heartbeat.api-key", apiKey);
    this.saveConfig();
    AccountAnvilController controller = this.accountController;
    if (controller != null) {
      controller.updateClient(new StarxAccountClient(endpoint.baseUrl(), apiKey));
    }
    this.getLogger().info(
        "StarX heartbeat switched to live Velocity runtime endpoint " + endpoint.baseUrl());
  }

  static String heartbeatFailureMessage(Throwable error) {
    Throwable cause = Objects.requireNonNull(error, "error");
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    String message = cause.getMessage();
    return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
  }

  private void enablePlaceholderApi() {
    if (this.placeholders != null || this.session == null) {
      return;
    }
    if (!this.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      return;
    }
    StarxPlaceholderExpansion expansion = new StarxPlaceholderExpansion(
        this.session,
        this.getPluginMeta().getVersion());
    if (!expansion.register()) {
      throw new IllegalStateException("PlaceholderAPI rejected the StarX expansion");
    }
    this.placeholders = expansion;
    this.getLogger().info("已解锁 PlaceholderAPI：starx_* 子服变量");
  }

  private void refreshSkinsRestorer() {
    if (!this.getServer().getPluginManager().isPluginEnabled("SkinsRestorer")) {
      this.skinResolver = null;
      this.skinResolvers.clear();
      return;
    }

    SkinsRestorerBackendSkinResolver discovered =
        SkinsRestorerBackendSkinResolver.discover(this.getLogger());
    this.skinResolver = discovered;
    if (!discovered.available()) {
      this.skinResolvers.clear();
      return;
    }
    this.skinResolvers.replace(discovered);
    this.getLogger().info("已解锁 SkinsRestorer：子服皮肤数据桥接可用");
  }

  private Map<String, String> readStatus() {
    Map<String, String> status = new LinkedHashMap<>();
    status.put("online", Integer.toString(Bukkit.getOnlinePlayers().size()));
    status.put("max", Integer.toString(Bukkit.getMaxPlayers()));
    status.put("minecraft", Bukkit.getMinecraftVersion());
    status.put("implementation", Bukkit.getName());
    status.put("uptimeMillis", Long.toString(System.currentTimeMillis() - this.startedAt));
    status.put("serverType", Objects.requireNonNullElse(this.serverType, "backend"));
    Runtime runtime = Runtime.getRuntime();
    long usedBytes = runtime.totalMemory() - runtime.freeMemory();
    long maxBytes = Math.max(1L, runtime.maxMemory());
    status.put("memoryUsedMb", Long.toString(usedBytes / 1_048_576L));
    status.put("memoryMaxMb", Long.toString(maxBytes / 1_048_576L));
    status.put("memoryPercent", Long.toString(Math.min(100L, usedBytes * 100L / maxBytes)));
    status.put("tps", Double.toString(readTps()));
    status.put("mspt", Double.toString(readMspt()));
    SkinsRestorerBackendSkinResolver skins = this.skinResolver;
    status.put("skinProvider", skins == null ? "none" : skins.provider());
    status.put("skinBridge", skins != null && skins.available() ? "available" : "unavailable");
    BackendMaintenanceState currentMaintenance = this.maintenance;
    status.put("maintenance", Boolean.toString(
        currentMaintenance != null && currentMaintenance.enabled()));
    return status;
  }

  private void applyMaintenance(boolean enabled) {
    BackendMaintenanceState current = this.maintenance;
    if (current == null || !current.update(enabled)) {
      return;
    }
    this.getLogger().info("StarX network maintenance=" + enabled);
  }

  private void persistMaintenance(boolean enabled) {
    this.getConfig().set("network.maintenance", enabled);
    this.saveConfig();
  }

  static String inferServerType(String nodeId) {
    String inferred = nodeId.trim().toLowerCase(java.util.Locale.ROOT)
        .replaceFirst("[-_.]?\\d+$", "");
    return inferred.isBlank() ? "backend" : inferred;
  }

  private static double readTps() {
    try {
      Object value = Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
      if (value instanceof double[] samples && samples.length > 0 && Double.isFinite(samples[0])) {
        return Math.max(0.0, samples[0]);
      }
    } catch (ReflectiveOperationException ignored) {
      // Paper-compatible fallback below.
    }
    return 20.0;
  }

  private static double readMspt() {
    try {
      Object value = Bukkit.getServer().getClass()
          .getMethod("getAverageTickTime").invoke(Bukkit.getServer());
      if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
        return Math.max(0.0, number.doubleValue());
      }
    } catch (ReflectiveOperationException ignored) {
      // Older compatible servers do not expose an average tick-time accessor.
    }
    return 0.0;
  }

  private final class IntegrationListener implements Listener {

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
      if (event.getPlugin().getName().equals("PlaceholderAPI")) {
        enablePlaceholderApi();
      }
      if (event.getPlugin().getName().equals("SkinsRestorer")) {
        refreshSkinsRestorer();
      }
    }
  }
}
