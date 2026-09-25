package io.github.addxiaoyi.starx.velocity.module.playerlist;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.addxiaoyi.starx.common.database.JdbcBindingRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.common.session.JdbcPlayerSessionRepository;
import io.github.addxiaoyi.starx.common.session.PlayerSessionSummary;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.module.auth.AuthModule;
import io.github.addxiaoyi.starx.velocity.variable.PlayerIdentityMetrics;
import io.github.addxiaoyi.starx.velocity.variable.StarxPlayerContextFactory;
import io.github.addxiaoyi.starx.velocity.variable.StarxVariableService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class PlayerListModule implements VelocityModule {

  private static final Set<String> USER_VARIABLES = Set.of(
      "starx_registered", "starx_2fa_enabled", "starx_last_login", "starx_playtime",
      "starx_playtime_total",
      "starx_first_join", "starx_reputation", "starx_trust_level");
  private static final Set<String> BINDING_VARIABLES = Set.of(
      "starx_bind_qq", "starx_bind_discord", "starx_reputation", "starx_trust_level");
  private static final Set<String> SESSION_VARIABLES = Set.of(
      "starx_playtime", "starx_playtime_total", "starx_server_footprint", "starx_reputation",
      "starx_trust_level");
  private static final PlayerIdentityMetrics EMPTY_METRICS =
      new PlayerIdentityMetrics(0, 0, 0, "未评级");
  private static final long PLAYER_DATA_CACHE_NANOS = Duration.ofSeconds(1).toNanos();

  private final StarxVelocityPlugin plugin;
  private final JdbcUserRepository users;
  private final JdbcBindingRepository bindings;
  private final JdbcPlayerSessionRepository sessions;
  private final AuthModule authentication;
  private final StarxConfig.PlayerListConfig config;
  private final PlayerListRenderer renderer;
  private final StarxPlayerContextFactory contextFactory;
  private final Function<UUID, UUID> canonicalUuidResolver;
  private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;
  private final PlayerLatencyTracker latencyTracker;
  private final boolean needsUserData;
  private final boolean needsBindingData;
  private final boolean needsSessionData;
  private final boolean needsLatencyData;

  private Listener listener;
  private ScheduledTask refreshTask;
  private ScheduledTask latencyTask;
  private final Map<UUID, PlayerListRenderer.Content> lastSentContent = new ConcurrentHashMap<>();
  private final Map<UUID, CachedPlayerData> playerDataCache = new ConcurrentHashMap<>();
  private final Map<UUID, DisplayedLatency> displayedLatency = new ConcurrentHashMap<>();
  private volatile NetworkSnapshot networkSnapshot;
  private volatile long lastLatencySampleNanos;

  public PlayerListModule(
      StarxVelocityPlugin plugin,
      JdbcUserRepository users,
      JdbcBindingRepository bindings,
      JdbcPlayerSessionRepository sessions,
      AuthModule authentication,
      StarxConfig.PlayerListConfig config,
      PlayerListRenderer renderer,
      StarxPlayerContextFactory contextFactory) {
    this(plugin, users, bindings, sessions, authentication, config, renderer, contextFactory,
        uuid -> uuid, uuid -> Set.of(uuid), new PlayerLatencyTracker());
  }

  public PlayerListModule(
      StarxVelocityPlugin plugin,
      JdbcUserRepository users,
      JdbcBindingRepository bindings,
      JdbcPlayerSessionRepository sessions,
      AuthModule authentication,
      StarxConfig.PlayerListConfig config,
      PlayerListRenderer renderer,
      StarxPlayerContextFactory contextFactory,
      Function<UUID, UUID> canonicalUuidResolver) {
    this(plugin, users, bindings, sessions, authentication, config, renderer, contextFactory,
        canonicalUuidResolver, uuid -> Set.of(uuid), new PlayerLatencyTracker());
  }

  public PlayerListModule(
      StarxVelocityPlugin plugin,
      JdbcUserRepository users,
      JdbcBindingRepository bindings,
      JdbcPlayerSessionRepository sessions,
      AuthModule authentication,
      StarxConfig.PlayerListConfig config,
      PlayerListRenderer renderer,
      StarxPlayerContextFactory contextFactory,
      Function<UUID, UUID> canonicalUuidResolver,
      Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
    this(plugin, users, bindings, sessions, authentication, config, renderer, contextFactory,
        canonicalUuidResolver, knownMinecraftUuidsResolver, new PlayerLatencyTracker());
  }

  public PlayerListModule(
      StarxVelocityPlugin plugin,
      JdbcUserRepository users,
      JdbcBindingRepository bindings,
      JdbcPlayerSessionRepository sessions,
      AuthModule authentication,
      StarxConfig.PlayerListConfig config,
      PlayerListRenderer renderer,
      StarxPlayerContextFactory contextFactory,
      Function<UUID, UUID> canonicalUuidResolver,
      Function<UUID, Set<UUID>> knownMinecraftUuidsResolver,
      PlayerLatencyTracker latencyTracker) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.users = Objects.requireNonNull(users, "users");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.authentication = Objects.requireNonNull(authentication, "authentication");
    this.config = Objects.requireNonNull(config, "config");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
    this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
    this.knownMinecraftUuidsResolver = Objects.requireNonNull(
        knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
    this.latencyTracker = Objects.requireNonNull(latencyTracker, "latencyTracker");
    Set<String> referenced = this.rendererVariables();
    this.needsBindingData = referencesAny(referenced, BINDING_VARIABLES);
    this.needsSessionData = referencesAny(referenced, SESSION_VARIABLES);
    this.needsLatencyData = referenced.contains("starx_ping")
        || referenced.contains("starx_ping_raw");
    this.needsUserData = referencesAny(referenced, USER_VARIABLES)
        || this.needsBindingData || this.needsSessionData;
  }

  @Override
  public String name() {
    return "starx.player-list";
  }

  @Override
  public void onEnable() {
    this.renderer.render(
        this.config,
        StarxVariableService.PlayerContext.guest("配置校验", 0));
    this.listener = new Listener();
    this.plugin.proxy().getEventManager().register(this.plugin, this.listener);
    this.refreshTask = this.plugin.proxy().getScheduler()
        .buildTask(this.plugin, this::refreshAll)
        .repeat(Duration.ofSeconds(this.config.refreshSeconds()))
        .schedule();
    this.latencyTask = this.plugin.proxy().getScheduler()
        .buildTask(this.plugin, this::samplePlayerLatencyAndRefresh)
        .repeat(Duration.ofMillis(250))
        .schedule();
    this.refreshAll();
    this.plugin.logger().info("内置玩家列表已启用，无需 TAB 或 PlaceholderAPI");
  }

  @Override
  public void onDisable() {
    ScheduledTask task = this.refreshTask;
    this.refreshTask = null;
    if (task != null) {
      task.cancel();
    }
    ScheduledTask currentLatencyTask = this.latencyTask;
    this.latencyTask = null;
    if (currentLatencyTask != null) {
      currentLatencyTask.cancel();
    }
    Listener currentListener = this.listener;
    this.listener = null;
    if (currentListener != null) {
      this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
    }
    this.lastSentContent.clear();
    this.displayedLatency.clear();
    this.playerDataCache.clear();
    this.plugin.proxy().getAllPlayers().forEach(player ->
        player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty()));
  }

  private void refreshAll() {
    samplePlayerLatency();
    NetworkSnapshot snapshot = this.refreshNetworkSnapshot();
    this.plugin.proxy().getAllPlayers().forEach(player -> this.refreshSafely(player, snapshot));
  }

  private void refreshSafely(Player player) {
    this.refreshSafely(player, this.refreshNetworkSnapshot());
  }

  private void refreshSafely(Player player, NetworkSnapshot snapshot) {
    try {
      StarxVariableService.PlayerContext context = this.contextFor(player, snapshot);
      PlayerListRenderer.Content content = this.renderer.render(this.config, context);
      content = accessibleContent(player, context, content);
      PlayerListRenderer.Content previous = this.lastSentContent.put(player.getUniqueId(), content);
      if (!content.equals(previous)) {
        player.sendPlayerListHeaderAndFooter(content.header(), content.footer());
      }
    } catch (RuntimeException error) {
      this.plugin.logger().log(
          Level.WARNING,
          "无法刷新玩家 " + player.getUsername() + " 的内置玩家列表",
          error);
    }
  }

  private static PlayerListRenderer.Content accessibleContent(
      Player player,
      StarxVariableService.PlayerContext context,
      PlayerListRenderer.Content content) {
    boolean lowVersion = player.getProtocolVersion().getProtocol()
        < com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_16.getProtocol();
    if (!context.bedrock() && !lowVersion) return content;
    PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
    return new PlayerListRenderer.Content(
        Component.text(plain.serialize(content.header())),
        Component.text(plain.serialize(content.footer())));
  }

  public StarxVariableService.PlayerContext contextFor(Player player) {
    return this.contextFor(player, this.currentNetworkSnapshot());
  }

  /** Local observations only; this method never initiates a network probe. */
  public Map<String, Object> networkMetrics() {
    int p95 = this.latencyTracker.percentile(95);
    return Map.of(
        "playerPingP95Ms", p95 < 0 ? "unknown" : p95,
        "playerPingSamples", this.latencyTracker.size());
  }

  private StarxVariableService.PlayerContext contextFor(Player player, NetworkSnapshot snapshot) {
    Objects.requireNonNull(player, "player");
    UUID playerId = player.getUniqueId();
    long now = System.nanoTime();
    CachedPlayerData cached = this.playerDataCache.get(playerId);
    CachedPlayerData data = cached != null && now - cached.cachedAtNanos() < PLAYER_DATA_CACHE_NANOS
        ? cached
        : this.loadPlayerData(playerId, now);
    StarxUser user = data.user();
    PlayerBinding binding = data.binding();
    PlayerSessionSummary session = data.session();
    Map<String, Long> playtime = data.playtime();
    String serverName = player.getCurrentServer()
        .map(connection -> connection.getServerInfo().getName())
        .orElse(null);
    String displayServerName = this.config.serverAlias(serverName);
    PlayerLatencyTracker.Snapshot latency = this.latencyTracker.snapshot(player.getUniqueId());
    int serverOnlinePlayers = snapshot.onlinePlayers(serverName);
    PlayerIdentityMetrics metrics = this.needsUserData || this.needsBindingData || this.needsSessionData
        ? PlayerIdentityMetrics.from(user, binding, session, playtime, Instant.now())
        : EMPTY_METRICS;
    return this.contextFactory.create(
        playerId,
        player.getUsername(),
        player.isOnlineMode(),
        this.authentication.requiresAuth(player),
        user,
        binding,
        displayServerName,
        snapshot.onlinePlayers(),
        snapshot.maxPlayers(),
        serverOnlinePlayers,
        0,
        metrics,
        snapshot.onlineServers(), latency.rawPing(), latency.smoothedPing());
  }

  private CachedPlayerData loadPlayerData(UUID playerId, long now) {
    StarxUser user = this.needsUserData
        ? this.authentication.authService().findConnectedUser(playerId).orElse(null)
        : null;
    UUID legacyUuid = user == null ? null : user.uuid();
    Set<UUID> knownUuids = this.needsBindingData || this.needsSessionData
        ? knownUuids(playerId, legacyUuid)
        : Set.of();
    PlayerBinding binding = null;
    if (this.needsBindingData) {
      for (UUID knownUuid : knownUuids) {
        binding = this.bindings.findByPlayer(knownUuid).orElse(null);
        if (binding != null) break;
      }
    }
    PlayerSessionSummary session = this.needsSessionData
        ? this.sessions.summary(knownUuids).orElse(null)
        : null;
    Map<String, Long> playtime = this.needsSessionData
        ? this.sessions.playtimeByServer(knownUuids)
        : Map.of();
    CachedPlayerData loaded = new CachedPlayerData(user, binding, session, playtime, now);
    this.playerDataCache.put(playerId, loaded);
    return loaded;
  }

  private NetworkSnapshot currentNetworkSnapshot() {
    NetworkSnapshot current = this.networkSnapshot;
    return current == null ? this.refreshNetworkSnapshot() : current;
  }

  private Set<String> rendererVariables() {
    return this.renderer.variables().referencedKeys(this.config.header(), this.config.footer());
  }

  private Set<UUID> knownUuids(UUID playerUuid, UUID legacyUuid) {
    Set<UUID> known = new LinkedHashSet<>(this.knownMinecraftUuidsResolver.apply(playerUuid));
    known.add(playerUuid);
    UUID canonical = this.canonicalUuidResolver.apply(legacyUuid == null ? playerUuid : legacyUuid);
    known.add(canonical);
    if (legacyUuid != null && !legacyUuid.equals(playerUuid)) {
      known.addAll(this.knownMinecraftUuidsResolver.apply(legacyUuid));
      known.add(legacyUuid);
    }
    return Set.copyOf(known);
  }

  private static boolean referencesAny(Set<String> referenced, Set<String> candidates) {
    return referenced.stream().anyMatch(candidates::contains);
  }

  private NetworkSnapshot refreshNetworkSnapshot() {
    Map<String, Integer> serverCounts = new LinkedHashMap<>();
    List<OnlineServer> online = new ArrayList<>();
    this.plugin.proxy().getAllServers().forEach(server -> {
      String name = server.getServerInfo().getName();
      int players = server.getPlayersConnected().size();
      serverCounts.put(name, players);
      online.add(new OnlineServer(this.config.serverAlias(name), players));
    });
    online.sort(Comparator.comparing(OnlineServer::name));
    StringBuilder labels = new StringBuilder();
    for (int index = 0; index < online.size(); index++) {
      OnlineServer server = online.get(index);
      if (!labels.isEmpty()) {
        labels.append(index % 4 == 0 ? "\\n" : " <dark_gray>|</dark_gray> ");
      }
      labels.append(server.name()).append(' ').append(server.players());
    }
    NetworkSnapshot snapshot = new NetworkSnapshot(
        this.plugin.proxy().getPlayerCount(),
        this.plugin.proxy().getConfiguration().getShowMaxPlayers(),
        serverCounts,
        labels.isEmpty() ? "暂无已注册子服" : labels.toString());
    this.networkSnapshot = snapshot;
    return snapshot;
  }

  private void samplePlayerLatency() {
    long now = System.nanoTime();
    if (now - this.lastLatencySampleNanos < Duration.ofMillis(250).toNanos()) return;
    this.lastLatencySampleNanos = now;
    Set<UUID> onlinePlayers = this.plugin.proxy().getAllPlayers().stream()
        .map(Player::getUniqueId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    this.latencyTracker.retain(onlinePlayers);
    this.plugin.proxy().getAllPlayers().forEach(player ->
        this.latencyTracker.sampleIfDue(player.getUniqueId(), player::getPing));
  }

  private void samplePlayerLatencyAndRefresh() {
    long now = System.nanoTime();
    if (now - this.lastLatencySampleNanos < Duration.ofMillis(250).toNanos()) return;
    this.lastLatencySampleNanos = now;
    Set<UUID> onlinePlayers = this.plugin.proxy().getAllPlayers().stream()
        .map(Player::getUniqueId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    this.latencyTracker.retain(onlinePlayers);
    NetworkSnapshot snapshot = this.currentNetworkSnapshot();
    this.plugin.proxy().getAllPlayers().forEach(player -> {
      UUID playerId = player.getUniqueId();
      PlayerLatencyTracker.Snapshot latency = this.latencyTracker.sampleIfDue(playerId, player::getPing);
      DisplayedLatency previous = this.displayedLatency.get(playerId);
      boolean changedEnough = previous == null
          || previous.smoothedPing() < 0 != latency.smoothedPing() < 0
          || Math.abs(previous.smoothedPing() - latency.smoothedPing()) >= 5
          || now - previous.displayedAtNanos() >= Duration.ofSeconds(1).toNanos();
      if (changedEnough && this.needsLatencyData) {
        this.displayedLatency.put(playerId, new DisplayedLatency(latency.smoothedPing(), now));
        this.refreshSafely(player, snapshot);
      }
    });
  }

  private record OnlineServer(String name, int players) {}

  private record NetworkSnapshot(
      int onlinePlayers,
      int maxPlayers,
      Map<String, Integer> serverCounts,
      String onlineServers) {
    private NetworkSnapshot {
      serverCounts = Map.copyOf(serverCounts);
    }

    private int onlinePlayers(String serverName) {
      return serverName == null ? 0 : serverCounts.getOrDefault(serverName, 0);
    }
  }

  private void scheduleRefresh(Player player) {
    this.plugin.proxy().getScheduler()
        .buildTask(this.plugin, () -> this.refreshSafely(player))
        .schedule();
  }

  private final class Listener {

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
      playerDataCache.remove(event.getPlayer().getUniqueId());
      scheduleRefresh(event.getPlayer());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
      playerDataCache.remove(event.getPlayer().getUniqueId());
      scheduleRefresh(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
      lastSentContent.remove(event.getPlayer().getUniqueId());
      displayedLatency.remove(event.getPlayer().getUniqueId());
      playerDataCache.remove(event.getPlayer().getUniqueId());
      latencyTracker.remove(event.getPlayer().getUniqueId());
    }
  }

  private record DisplayedLatency(int smoothedPing, long displayedAtNanos) {}

  private record CachedPlayerData(
      StarxUser user,
      PlayerBinding binding,
      PlayerSessionSummary session,
      Map<String, Long> playtime,
      long cachedAtNanos) {
    private CachedPlayerData {
      playtime = Map.copyOf(playtime);
    }
  }
}
