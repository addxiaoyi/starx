package io.github.addxiaoyi.starx.velocity.website;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.platform.NodeHealthStateMachine;
import io.github.addxiaoyi.starx.common.skin.SkinsRestorerSkinRepository;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNode;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import io.github.addxiaoyi.starx.velocity.bridge.VelocityBackendBridge;
import io.github.addxiaoyi.starx.velocity.module.proxytools.MaintenanceModule;
import io.github.addxiaoyi.starx.website.NodeCapabilities;
import io.github.addxiaoyi.starx.website.NodeSnapshot;
import io.github.addxiaoyi.starx.website.ServerSnapshot;
import io.github.addxiaoyi.starx.website.TextureSource;
import io.github.addxiaoyi.starx.website.WebsiteNodeStatus;
import io.github.addxiaoyi.starx.website.WebsiteSyncConfig;
import io.github.addxiaoyi.starx.website.WebsiteSyncHttpClient;
import io.github.addxiaoyi.starx.website.WebsiteSyncRuntime;
import io.github.addxiaoyi.starx.website.YamlWebsiteCredentialStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Consumer;

public final class VelocityWebsiteSync implements AutoCloseable {
  static final Duration CHILD_OFFLINE_AFTER = Duration.ofSeconds(90);

  private final StarxVelocityPlugin plugin;
  private final VelocityBackendBridge bridge;
  private final MaintenanceModule maintenance;
  private final WebsiteSyncRuntime runtime;

  public VelocityWebsiteSync(
      StarxVelocityPlugin plugin,
      VelocityBackendBridge bridge,
      MaintenanceModule maintenance,
      JdbcUserRepository userRepository
  ) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
    this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    WebsiteSyncConfig config = plugin.config().websiteSync();
    this.runtime = new WebsiteSyncRuntime(
        config,
        new WebsiteSyncHttpClient(config),
        new YamlWebsiteCredentialStore(plugin.dataDirectory().resolve("config.yml")),
        this::currentSnapshot,
        textureSource(plugin, config, Objects.requireNonNull(userRepository, "userRepository")),
        List.of(
            NodeCapabilities.NETWORK_STATUS,
            NodeCapabilities.PUBLIC_PLAYER_COUNT,
            NodeCapabilities.PLAYERS_SNAPSHOT,
            NodeCapabilities.SERVER_STATUS,
            NodeCapabilities.SKIN_REFRESH),
        plugin.logger()::info);
  }

  private static TextureSource textureSource(
      StarxVelocityPlugin plugin,
      WebsiteSyncConfig config,
      JdbcUserRepository userRepository
  ) {
    if (!config.textures().enabled()) {
      return TextureSource.empty();
    }
    if (!"skinsrestorer".equals(config.textures().source())) {
      throw new IllegalArgumentException(
          "Unsupported Velocity website texture source: " + config.textures().source());
    }
    Consumer<String> logger = plugin.logger()::info;
    return new SkinsRestorerTextureSource(
        () -> SkinsRestorerTextureSource.mergePlayers(
            historicalPlayers(userRepository, logger),
            plugin.proxy().getAllPlayers().stream()
                .map(player -> new SkinsRestorerTextureSource.PlayerRef(
                    player.getUniqueId(), player.getUsername()))
                .toList()),
        new SkinsRestorerSkinRepository(),
        config.heartbeat(),
        logger);
  }

  private static List<SkinsRestorerTextureSource.PlayerRef> historicalPlayers(
      JdbcUserRepository userRepository,
      Consumer<String> logger
  ) {
    try {
      return userRepository.findAll().stream()
          .map(user -> new SkinsRestorerTextureSource.PlayerRef(
              user.uuid(), user.username()))
          .toList();
    } catch (RuntimeException error) {
      logger.accept("StarX website texture history lookup failed; using online players only: "
          + error.getClass().getSimpleName());
      return List.of();
    }
  }

  public void start() {
    this.runtime.start();
  }

  public WebsiteSyncRuntime.Snapshot status() {
    return this.runtime.snapshot();
  }

  @Override
  public void close() {
    this.runtime.close();
  }

  private NodeSnapshot currentSnapshot() {
    Collection<String> configuredServers = this.plugin.proxy().getAllServers().stream()
        .map(server -> server.getServerInfo().getName())
        .toList();
    return buildSnapshot(
        implementationVersion(this.plugin),
        this.plugin.proxy().getPlayerCount(),
        this.plugin.proxy().getConfiguration().getShowMaxPlayers(),
        this.maintenance.isEnabled(),
        configuredServers,
        this.bridge.registry(),
        Instant.now());
  }

  static NodeSnapshot buildSnapshot(
      String pluginVersion,
      int onlinePlayers,
      int maxPlayers,
      boolean maintenance,
      Collection<String> configuredServers,
      BackendNodeRegistry registry,
      Instant now
  ) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(now, "now");
    TreeSet<String> names = new TreeSet<>();
    if (configuredServers != null) {
      configuredServers.stream()
          .filter(Objects::nonNull)
          .map(String::trim)
          .filter(name -> !name.isEmpty())
          .forEach(names::add);
    }
    names.addAll(registry.serverNames());
    if (names.size() > 128) {
      throw new IllegalStateException(
          "Velocity exposes " + names.size() + " child servers; website protocol allows 128");
    }
    List<ServerSnapshot> children = new ArrayList<>(names.size());
    for (String registeredName : names) {
      BackendNode node = registry.find(registeredName).orElse(null);
      children.add(node == null
          ? unseenServer(registeredName)
          : knownServer(registeredName, node, registry, now));
    }
    return new NodeSnapshot(
        pluginVersion,
        null,
        boundedCount(onlinePlayers),
        boundedCount(maxPlayers),
        null,
        null,
        maintenance,
        children);
  }

  private static ServerSnapshot unseenServer(String registeredName) {
    return new ServerSnapshot(
        registeredName,
        registeredName,
        null,
        null,
        WebsiteNodeStatus.OFFLINE,
        0,
        null,
        null,
        null,
        false,
        List.of());
  }

  private static ServerSnapshot knownServer(
      String registeredName,
      BackendNode node,
      BackendNodeRegistry registry,
      Instant now
  ) {
    boolean stale = node.isStale(now, CHILD_OFFLINE_AFTER);
    boolean maintenance = Boolean.parseBoolean(node.status().getOrDefault("maintenance", "false"));
    NodeHealthStateMachine.State health = registry.health(registeredName).state();
    WebsiteNodeStatus status;
    if (stale || health == NodeHealthStateMachine.State.OFFLINE) {
      status = WebsiteNodeStatus.OFFLINE;
    } else if (maintenance) {
      status = WebsiteNodeStatus.MAINTENANCE;
    } else if (health == NodeHealthStateMachine.State.SUSPECT
        || health == NodeHealthStateMachine.State.DRAINING
        || health == NodeHealthStateMachine.State.WARMING) {
      status = WebsiteNodeStatus.DEGRADED;
    } else {
      status = WebsiteNodeStatus.ONLINE;
    }
    boolean offline = status == WebsiteNodeStatus.OFFLINE;
    Integer online = offline ? 0 : nullableCount(node.onlinePlayers());
    return new ServerSnapshot(
        node.declaredNodeId(),
        registeredName,
        platform(node.platform()),
        blankToNull(node.status().get("minecraft")),
        status,
        online,
        nullableCount(node.maxPlayers()),
        offline ? null : boundedDouble(node.status().get("tps"), 0, 100),
        offline ? null : boundedDouble(node.status().get("mspt"), 0, 60_000),
        maintenance,
        NodeCapabilities.filterSupported(node.capabilities()));
  }

  private static String implementationVersion(StarxVelocityPlugin plugin) {
    return plugin.proxy().getPluginManager().fromInstance(plugin)
        .flatMap(container -> container.getDescription().getVersion())
        .orElse("unknown");
  }

  private static String platform(PlatformKind platform) {
    return platform.name().toLowerCase(Locale.ROOT);
  }

  private static Integer boundedCount(int value) {
    return value < 0 || value > 100_000 ? null : value;
  }

  private static Integer nullableCount(int value) {
    return value < 0 ? null : boundedCount(value);
  }

  private static Double boundedDouble(String value, double minimum, double maximum) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      double parsed = Double.parseDouble(value);
      return Double.isFinite(parsed) && parsed >= minimum && parsed <= maximum ? parsed : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
