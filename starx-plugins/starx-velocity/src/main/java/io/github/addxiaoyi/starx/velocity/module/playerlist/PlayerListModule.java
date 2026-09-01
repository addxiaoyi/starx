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
  private final boolean needsUserData;
  private final boolean needsBindingData;
  private final boolean needsSessionData;

  private Listener listener;
  private ScheduledTask refreshTask;
  private final Map<UUID, PlayerListRenderer.Content> lastSentContent = new ConcurrentHashMap<>();
  private volatile NetworkSnapshot networkSnapshot;

  public PlayerListModule(
      StarxVelocityPlugin plugin,
      JdbcUserRepository users,
      JdbcBindingRepository bindings,
      JdbcPlayerSessionRepository sessions,
      AuthModule authentication,
      StarxConfig.PlayerListConfig config,
      PlayerListRenderer renderer,
      StarxPlayerContextFactory contextFactory) {
    this(plugin, users, bindings, sessions, authentication, config, renderer, contextFactory, uuid -> uuid);
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
        canonicalUuidResolver, uuid -> Set.of(uuid));
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
    Set<String> referenced = this.rendererVariables();
    this.needsBindingData = referencesAny(referenced, BINDING_VARIABLES);
    this.needsSessionData = referencesAny(referenced, SESSION_VARIABLES);
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
    Listener currentListener = this.listener;
    this.listener = null;
    if (currentListener != null) {
      this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
    }
    this.lastSentContent.clear();
    this.plugin.proxy().getAllPlayers().forEach(player ->
        player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty()));
  }

  private void refreshAll() {
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

  public StarxVariableService.PlayerContext contextFor(Player player) {
    return this.contextFor(player, this.currentNetworkSnapshot());
  }

  private StarxVariableService.PlayerContext contextFor(Player player, NetworkSnapshot snapshot) {
    Objects.requireNonNull(player, "player");
    StarxUser user = this.needsUserData
        ? this.authentication.authService().findConnectedUser(player.getUniqueId()).orElse(null)
        : null;
    UUID legacyUuid = user == null ? null : user.uuid();
    Set<UUID> knownUuids = this.needsBindingData || this.needsSessionData
        ? knownUuids(player.getUniqueId(), legacyUuid)
        : Set.of();
    PlayerBinding binding = null;
    if (this.needsBindingData) {
      for (UUID knownUuid : knownUuids) {
        binding = this.bindings.findByPlayer(knownUuid).orElse(null);
        if (binding != null) break;
      }
    }
    var session = this.needsSessionData ? this.sessions.summary(knownUuids).orElse(null) : null;
    var playtime = this.needsSessionData ? this.sessions.playtimeByServer(knownUuids) : Map.<String, Long>of();
    String serverName = player.getCurrentServer()
        .map(connection -> connection.getServerInfo().getName())
        .orElse(null);
    String displayServerName = this.config.serverAlias(serverName);
    int serverOnlinePlayers = snapshot.onlinePlayers(serverName);
    PlayerIdentityMetrics metrics = this.needsUserData || this.needsBindingData || this.needsSessionData
        ? PlayerIdentityMetrics.from(user, binding, session, playtime, Instant.now())
        : EMPTY_METRICS;
    return this.contextFactory.create(
        player.getUniqueId(),
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
        snapshot.onlineServers());
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
      if (players > 0) {
        online.add(new OnlineServer(this.config.serverAlias(name), players));
      }
    });
    online.sort(Comparator.comparingInt(OnlineServer::players).reversed()
        .thenComparing(OnlineServer::name));
    StringBuilder labels = new StringBuilder();
    for (OnlineServer server : online) {
      if (!labels.isEmpty()) {
        labels.append(" <dark_gray>|</dark_gray> ");
      }
      labels.append(server.name()).append(' ').append(server.players());
    }
    NetworkSnapshot snapshot = new NetworkSnapshot(
        this.plugin.proxy().getPlayerCount(),
        this.plugin.proxy().getConfiguration().getShowMaxPlayers(),
        serverCounts,
        labels.isEmpty() ? "暂无在线子服" : labels.toString());
    this.networkSnapshot = snapshot;
    return snapshot;
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
      scheduleRefresh(event.getPlayer());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
      scheduleRefresh(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
      lastSentContent.remove(event.getPlayer().getUniqueId());
    }
  }
}
