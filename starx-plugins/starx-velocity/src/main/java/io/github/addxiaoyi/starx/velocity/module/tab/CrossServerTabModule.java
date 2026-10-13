package io.github.addxiaoyi.starx.velocity.module.tab;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.metrics.RuntimeWindowCounter;
import io.github.addxiaoyi.starx.velocity.module.playerlist.PlayerLatencyTracker;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Keeps the proxy-wide player roster visible in every backend TAB list. */
public final class CrossServerTabModule implements VelocityModule {

  private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(5);
  private static final Duration EVENT_DELAY = Duration.ofMillis(250);
  private static final int LATENCY_CHANGE_THRESHOLD_MS = 5;
  private static final long SERVER_META_TTL_NANOS = Duration.ofSeconds(30).toNanos();
  private static final long SERVER_PING_TIMEOUT_SECONDS = 2L;

  private final StarxVelocityPlugin plugin;
  private final PlayerLatencyTracker latencyTracker;
  private final ServerNameMappings serverNames;
  private final Map<UUID, Map<UUID, EntryState>> sentEntries = new ConcurrentHashMap<>();
  private final RuntimeWindowCounter reconcileCounter = new RuntimeWindowCounter();
  private final RuntimeWindowCounter playerScanCounter = new RuntimeWindowCounter();
  private final RuntimeWindowCounter tabWriteCounter = new RuntimeWindowCounter();
  private final Map<String, ServerMetadata> serverMetadata = new ConcurrentHashMap<>();
  private final ReentrantLock lifecycleLock = new ReentrantLock();
  private boolean reconcileQueued;
  private long generation;
  private ScheduledTask eventTask;
  private final Listener listener = new Listener();
  private volatile boolean enabled;
  private ScheduledTask reconcileTask;

  public CrossServerTabModule(StarxVelocityPlugin plugin) {
    this(plugin, new PlayerLatencyTracker());
  }

  public CrossServerTabModule(
      StarxVelocityPlugin plugin,
      PlayerLatencyTracker latencyTracker) {
    this.plugin = plugin;
    this.latencyTracker = latencyTracker;
    this.serverNames = new ServerNameMappings(
        plugin.dataDirectory().resolve("cross-server-tab.yml"), plugin.logger()::warning);
  }

  @Override
  public String name() {
    return "starx.cross-server-tab";
  }

  @Override
  public void onEnable() {
    this.lifecycleLock.lock();
    try {
      if (this.enabled) return;
      this.enabled = true;
      long epoch = ++this.generation;
      this.plugin.proxy().getEventManager().register(this.plugin, this.listener);
      this.reconcileTask = this.plugin.proxy().getScheduler()
          .buildTask(this.plugin, () -> reconcile(epoch))
          .repeat(RECONCILE_INTERVAL)
          .schedule();
      scheduleReconcile(Duration.ZERO);
    } finally {
      this.lifecycleLock.unlock();
    }
  }

  @Override
  public void onDisable() {
    this.lifecycleLock.lock();
    try {
      this.enabled = false;
      this.generation++;
      if (this.reconcileTask != null) this.reconcileTask.cancel();
      if (this.eventTask != null) this.eventTask.cancel();
      this.reconcileTask = null;
      this.eventTask = null;
      this.reconcileQueued = false;
      this.plugin.proxy().getEventManager().unregisterListener(this.plugin, this.listener);
      this.plugin.proxy().getAllPlayers().forEach(this::removeManagedEntries);
      this.sentEntries.clear();
      this.serverMetadata.clear();
    } finally {
      this.lifecycleLock.unlock();
    }
  }

  private void reconcile(long epoch) {
    if (!this.lifecycleLock.tryLock()) return;
    try {
      if (!this.enabled || epoch != this.generation) return;
      this.reconcileCounter.increment();
      if (this.serverNames.reloadIfChanged()) {
        this.plugin.logger().info("已热加载 cross-server-tab.yml（" + this.serverNames.size() + " 个映射）");
      }
      List<Player> players = this.plugin.proxy().getAllPlayers().stream()
          .filter(Player::isActive)
          .sorted(Comparator.comparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER)
              .thenComparing(Player::getUniqueId))
          .toList();
      this.playerScanCounter.add(players.size());
      this.sentEntries.keySet().retainAll(players.stream().map(Player::getUniqueId).collect(Collectors.toSet()));
      Map<UUID, EntryState> roster = new LinkedHashMap<>();
      players.forEach(player -> player.getCurrentServer().ifPresent(connection -> {
        String backendName = connection.getServer().getServerInfo().getName();
        roster.put(player.getUniqueId(), new EntryState(
            backendName,
            displayServerName(backendName),
            player.getUsername(),
            tabLatency(player),
            player.getGameProfile(),
            false));
      }));
      players.stream().filter(Player::isActive).forEach(viewer -> reconcileViewer(viewer, roster));
    } finally {
      this.lifecycleLock.unlock();
    }
  }

  private void reconcileViewer(Player viewer, Map<UUID, EntryState> roster) {
    UUID viewerId = viewer.getUniqueId();
    Map<UUID, EntryState> previous = this.sentEntries.computeIfAbsent(
        viewerId, ignored -> new ConcurrentHashMap<>());
    TabList tabList = viewer.getTabList();
    String viewerBackend = viewer.getCurrentServer()
        .map(connection -> connection.getServer().getServerInfo().getName())
        .orElse(null);
    Map<UUID, EntryState> desired = groupedEntries(roster, viewerId, viewerBackend);

    previous.keySet().removeIf(targetId -> {
      if (desired.containsKey(targetId)) {
        return false;
      }
      if (tabList.removeEntry(targetId).isPresent()) {
        this.tabWriteCounter.increment();
      }
      return true;
    });

    desired.forEach((targetId, state) -> {
      EntryState old = previous.get(targetId);
      if (old != null && old.sameDisplay(state)) {
        if (tabList.getEntry(targetId).isEmpty()) {
          if (addEntry(tabList, targetId, state)) {
            previous.put(targetId, state);
          }
          return;
        }
        if (old.shouldUpdateLatency(state)) {
          tabList.getEntry(targetId).ifPresent(entry -> entry.setLatency(state.latency()));
          previous.put(targetId, state);
        }
        return;
      }
      if (old != null) {
        tabList.removeEntry(targetId);
      }
      if (addEntry(tabList, targetId, state)) {
        previous.put(targetId, state);
      }
    });
  }

  private Map<UUID, EntryState> groupedEntries(
      Map<UUID, EntryState> roster,
      UUID viewerId,
      String viewerBackend) {
    Map<String, List<Map.Entry<UUID, EntryState>>> groups = roster.entrySet().stream()
        .collect(java.util.stream.Collectors.groupingBy(
            entry -> entry.getValue().backendName(), LinkedHashMap::new,
            Collectors.toList()));
    List<Map.Entry<String, List<Map.Entry<UUID, EntryState>>>> ordered = groups.entrySet().stream()
        .sorted(Comparator
            .comparing((Map.Entry<String, List<Map.Entry<UUID, EntryState>>> entry) ->
                !entry.getKey().equals(viewerBackend))
            .thenComparing(entry -> entry.getValue().getFirst().getValue().serverName(),
                String.CASE_INSENSITIVE_ORDER))
        .toList();
    Map<UUID, EntryState> desired = new LinkedHashMap<>();
    for (Map.Entry<String, List<Map.Entry<UUID, EntryState>>> group : ordered) {
      EntryState first = group.getValue().getFirst().getValue();
      int players = group.getValue().size();
      String header = serverHeaderLabel(group.getKey(), first.serverName(), players);
      UUID headerId = UUID.nameUUIDFromBytes(
          ("starx:tab:server:" + group.getKey()).getBytes(StandardCharsets.UTF_8));
      desired.put(headerId, EntryState.header(
          group.getKey(), header, headerId));
      group.getValue().stream()
          .sorted(Comparator.comparing(entry -> entry.getValue().username(),
              String.CASE_INSENSITIVE_ORDER))
          .filter(entry -> !entry.getKey().equals(viewerId))
          .forEach(entry -> desired.put(entry.getKey(), entry.getValue()));
    }
    return desired;
  }

  private String serverHeaderLabel(String backendName, String displayName, int players) {
    ServerMetadata metadata = this.serverMetadata.computeIfAbsent(
        backendName, ignored -> new ServerMetadata());
    refreshServerMetadataIfDue(backendName, metadata);
    int maxPlayers = metadata.maxPlayers;
    String capacity = maxPlayers > 0 ? players + "/" + maxPlayers : Integer.toString(players);
    String status = metadata.online ? "在线" : "未知";
    return displayName + " " + capacity + " " + status;
  }

  private void refreshServerMetadataIfDue(String backendName, ServerMetadata metadata) {
    long now = System.nanoTime();
    if (now - metadata.refreshedAtNanos < SERVER_META_TTL_NANOS
        || !metadata.inFlight.compareAndSet(false, true)) {
      return;
    }
    RegisteredServer server = this.plugin.proxy().getServer(backendName).orElse(null);
    if (server == null) {
      metadata.inFlight.set(false);
      metadata.refreshedAtNanos = now;
      return;
    }
    CompletableFuture<ServerPing> ping;
    try {
      ping = server.ping();
    } catch (RuntimeException error) {
      metadata.online = false;
      metadata.refreshedAtNanos = now;
      metadata.inFlight.set(false);
      return;
    }
    ping.orTimeout(SERVER_PING_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((result, error) -> {
      if (error == null && result != null) {
        metadata.online = true;
        metadata.maxPlayers = result.getPlayers().map(ServerPing.Players::getMax).orElse(-1);
      } else {
        metadata.online = false;
      }
      metadata.refreshedAtNanos = System.nanoTime();
      metadata.inFlight.set(false);
      scheduleReconcile(Duration.ZERO);
    });
  }

  private static final class ServerMetadata {
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile long refreshedAtNanos;
    private volatile int maxPlayers = -1;
    private volatile boolean online = true;
  }

  private boolean addEntry(TabList tabList, UUID targetId, EntryState state) {
    var existing = tabList.getEntry(targetId);
    Component display = displayName(state);
    if (existing.isPresent()) {
      var entry = existing.get();
      if (!entry.getDisplayNameComponent().map(display::equals).orElse(false)) {
        entry.setDisplayName(display);
        this.tabWriteCounter.increment();
      }
      if (entry.getLatency() != state.latency()) {
        entry.setLatency(state.latency());
        this.tabWriteCounter.increment();
      }
      return true;
    }
    tabList.addEntry(tabList.buildEntry(state.gameProfile(), display, state.latency(), 0));
    this.tabWriteCounter.increment();
    return true;
  }

  private void removeManagedEntries(Player viewer) {
    Map<UUID, EntryState> entries = this.sentEntries.remove(viewer.getUniqueId());
    if (entries == null) {
      return;
    }
    TabList tabList = viewer.getTabList();
    entries.forEach((targetId, state) -> {
      if (state.serverHeader()) {
        if (tabList.removeEntry(targetId).isPresent()) {
          this.tabWriteCounter.increment();
        }
        return;
      }
      Player target = this.plugin.proxy().getPlayer(targetId).orElse(null);
      if (target != null && sameBackend(viewer, target)) {
        tabList.getEntry(targetId).ifPresent(entry ->
            entry.setDisplayName(Component.text(state.username(), NamedTextColor.WHITE)));
        return;
      }
      if (tabList.removeEntry(targetId).isPresent()) {
        this.tabWriteCounter.increment();
      }
    });
  }

  private static boolean sameBackend(Player viewer, Player target) {
    return viewer.getCurrentServer().flatMap(viewerConnection ->
        target.getCurrentServer().map(targetConnection ->
            viewerConnection.getServer().getServerInfo().getName().equals(
                targetConnection.getServer().getServerInfo().getName())))
        .orElse(false);
  }

  private void scheduleReconcile(Duration delay) {
    // The periodic task covers skipped events; never stall a player event behind a refresh.
    if (!this.lifecycleLock.tryLock()) return;
    try {
      if (!this.enabled || this.reconcileQueued) return;
      this.reconcileQueued = true;
      long epoch = this.generation;
      this.eventTask = this.plugin.proxy().getScheduler().buildTask(this.plugin, () -> {
        this.lifecycleLock.lock();
        try {
          if (!this.enabled || epoch != this.generation) return;
          this.reconcileQueued = false;
          this.eventTask = null;
          reconcile(epoch);
        } finally {
          this.lifecycleLock.unlock();
        }
      }).delay(delay).schedule();
    } finally {
      this.lifecycleLock.unlock();
    }
  }

  private static Component displayName(EntryState state) {
    if (state.serverHeader()) {
      return Component.text("[" + state.serverName() + "]", NamedTextColor.AQUA);
    }
    return Component.text(state.username(), NamedTextColor.WHITE);
  }

  private String displayServerName(String serverName) {
    return this.serverNames.resolve(serverName);
  }

  private int tabLatency(Player player) {
    return this.latencyTracker.sampleIfDue(player.getUniqueId(), player::getPing).smoothedPing();
  }

  public Map<String, Object> runtimeMetrics() {
    return Map.of(
        "reconcilePerMinute", this.reconcileCounter.perMinute(),
        "reconcileTotal", this.reconcileCounter.total(),
        "playerScansPerMinute", this.playerScanCounter.perMinute(),
        "playerScansTotal", this.playerScanCounter.total(),
        "tabWritesPerMinute", this.tabWriteCounter.perMinute(),
        "tabWritesTotal", this.tabWriteCounter.total(),
        "reconcileIntervalSeconds", RECONCILE_INTERVAL.toSeconds());
  }

  private final class Listener {
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
      scheduleReconcile(EVENT_DELAY);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
      scheduleReconcile(EVENT_DELAY);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
      UUID playerId = event.getPlayer().getUniqueId();
      sentEntries.remove(playerId);
      latencyTracker.remove(playerId);
      scheduleReconcile(Duration.ZERO);
    }
  }

  private record EntryState(
      String backendName,
      String serverName,
      String username,
      int latency,
      com.velocitypowered.api.util.GameProfile gameProfile,
      boolean serverHeader) {
    private static EntryState header(String backendName, String serverName, UUID headerId) {
      return new EntryState(
          backendName,
          serverName,
          "",
          -1,
          new com.velocitypowered.api.util.GameProfile(headerId, "StarX", List.of()),
          true);
    }

    private boolean sameDisplay(EntryState other) {
      return this.serverHeader == other.serverHeader
          && this.serverName.equals(other.serverName)
          && this.username.equals(other.username);
    }

    private boolean shouldUpdateLatency(EntryState other) {
      if (this.latency < 0 || other.latency < 0) {
        return this.latency != other.latency;
      }
      return Math.abs(this.latency - other.latency) >= LATENCY_CHANGE_THRESHOLD_MS;
    }
  }
}
