package io.github.addxiaoyi.starx.velocity.module.tab;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.module.playerlist.PlayerLatencyTracker;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Keeps the proxy-wide player roster visible in every backend TAB list. */
public final class CrossServerTabModule implements VelocityModule {

  private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(1);
  private static final Duration EVENT_DELAY = Duration.ofMillis(250);
  private static final int LATENCY_CHANGE_THRESHOLD_MS = 5;

  private final StarxVelocityPlugin plugin;
  private final PlayerLatencyTracker latencyTracker;
  private final ServerNameMappings serverNames;
  private final Map<UUID, Map<UUID, EntryState>> sentEntries = new ConcurrentHashMap<>();
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
    } finally {
      this.lifecycleLock.unlock();
    }
  }

  private void reconcile(long epoch) {
    if (!this.lifecycleLock.tryLock()) return;
    try {
      if (!this.enabled || epoch != this.generation) return;
      if (this.serverNames.reloadIfChanged()) {
        this.plugin.logger().info("已热加载 cross-server-tab.yml（" + this.serverNames.size() + " 个映射）");
      }
      Map<UUID, EntryState> roster = new LinkedHashMap<>();
      this.plugin.proxy().getAllPlayers().stream()
          .sorted(Comparator.comparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER)
              .thenComparing(Player::getUniqueId))
          .forEach(player -> player.getCurrentServer().ifPresent(connection -> roster.put(
              player.getUniqueId(),
              new EntryState(displayServerName(connection.getServer().getServerInfo().getName()),
                  player.getUsername(), tabLatency(player), player.getGameProfile()))));
      this.plugin.proxy().getAllPlayers().forEach(viewer -> reconcileViewer(viewer, roster));
    } finally {
      this.lifecycleLock.unlock();
    }
  }

  private void reconcileViewer(Player viewer, Map<UUID, EntryState> roster) {
    UUID viewerId = viewer.getUniqueId();
    Map<UUID, EntryState> previous = this.sentEntries.computeIfAbsent(
        viewerId, ignored -> new ConcurrentHashMap<>());
    TabList tabList = viewer.getTabList();

    previous.keySet().removeIf(targetId -> {
      if (targetId.equals(viewerId) || roster.containsKey(targetId)) {
        return false;
      }
      tabList.removeEntry(targetId);
      return true;
    });

    roster.forEach((targetId, state) -> {
      if (targetId.equals(viewerId)) {
        return;
      }
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

  private boolean addEntry(TabList tabList, UUID targetId, EntryState state) {
    var existing = tabList.getEntry(targetId);
    if (existing.isPresent()) {
      existing.get().setDisplayName(displayName(state));
      existing.get().setLatency(state.latency());
      return true;
    }
    tabList.addEntry(tabList.buildEntry(
        state.gameProfile(), displayName(state), state.latency(), 0));
    return true;
  }

  private void removeManagedEntries(Player viewer) {
    Map<UUID, EntryState> entries = this.sentEntries.remove(viewer.getUniqueId());
    if (entries == null) {
      return;
    }
    TabList tabList = viewer.getTabList();
    entries.forEach((targetId, state) -> {
      Player target = this.plugin.proxy().getPlayer(targetId).orElse(null);
      if (target != null && sameBackend(viewer, target)) {
        tabList.getEntry(targetId).ifPresent(entry ->
            entry.setDisplayName(Component.text(state.username(), NamedTextColor.WHITE)));
        return;
      }
      tabList.removeEntry(targetId);
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
    return Component.text("[" + state.serverName() + "] ", NamedTextColor.AQUA)
        .append(Component.text(state.username(), NamedTextColor.WHITE));
  }

  private String displayServerName(String serverName) {
    return this.serverNames.resolve(serverName);
  }

  private int tabLatency(Player player) {
    return this.latencyTracker.sampleIfDue(player.getUniqueId(), player::getPing).smoothedPing();
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
      String serverName,
      String username,
      int latency,
      com.velocitypowered.api.util.GameProfile gameProfile) {
    private boolean sameDisplay(EntryState other) {
      return this.serverName.equals(other.serverName) && this.username.equals(other.username);
    }

    private boolean shouldUpdateLatency(EntryState other) {
      if (this.latency < 0 || other.latency < 0) {
        return this.latency != other.latency;
      }
      return Math.abs(this.latency - other.latency) >= LATENCY_CHANGE_THRESHOLD_MS;
    }
  }
}
