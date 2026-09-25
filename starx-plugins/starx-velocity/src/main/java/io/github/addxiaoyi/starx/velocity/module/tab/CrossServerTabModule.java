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
import java.time.Duration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.yaml.snakeyaml.Yaml;

/** Keeps the proxy-wide player roster visible in every backend TAB list. */
public final class CrossServerTabModule implements VelocityModule {

  private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(1);
  private static final Duration EVENT_DELAY = Duration.ofMillis(250);
  private static final int LATENCY_CHANGE_THRESHOLD_MS = 5;

  private final StarxVelocityPlugin plugin;
  private final Path serverNamesPath;
  private volatile Map<String, String> serverNames;
  private volatile long serverNamesModifiedAt = Long.MIN_VALUE;
  private volatile boolean serverNamesLoadValid;
  private final Map<UUID, Map<UUID, EntryState>> sentEntries = new ConcurrentHashMap<>();
  private final AtomicBoolean reconcileQueued = new AtomicBoolean();
  private final AtomicBoolean reconciling = new AtomicBoolean();
  private final Listener listener = new Listener();
  private volatile boolean enabled;
  private ScheduledTask reconcileTask;

  public CrossServerTabModule(StarxVelocityPlugin plugin) {
    this.plugin = plugin;
    this.serverNamesPath = plugin.dataDirectory().resolve("cross-server-tab.yml");
    this.serverNames = loadServerNames();
  }

  @Override
  public String name() {
    return "starx.cross-server-tab";
  }

  @Override
  public void onEnable() {
    this.enabled = true;
    this.plugin.proxy().getEventManager().register(this.plugin, this.listener);
    this.reconcileTask = this.plugin.proxy().getScheduler()
        .buildTask(this.plugin, this::reconcile)
        .repeat(RECONCILE_INTERVAL)
        .schedule();
    scheduleReconcile(Duration.ZERO);
  }

  @Override
  public void onDisable() {
    this.enabled = false;
    ScheduledTask task = this.reconcileTask;
    this.reconcileTask = null;
    if (task != null) {
      task.cancel();
    }
    this.plugin.proxy().getEventManager().unregisterListener(this.plugin, this.listener);
    this.plugin.proxy().getAllPlayers().forEach(this::removeManagedEntries);
    this.sentEntries.clear();
  }

  private void reconcile() {
    if (!this.reconciling.compareAndSet(false, true)) {
      return;
    }
    try {
      reloadServerNamesIfChanged();
      Map<UUID, EntryState> roster = new LinkedHashMap<>();
      this.plugin.proxy().getAllPlayers().stream()
          .sorted(Comparator.comparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER)
              .thenComparing(Player::getUniqueId))
          .forEach(player -> player.getCurrentServer().ifPresent(connection -> roster.put(
              player.getUniqueId(),
              new EntryState(displayServerName(connection.getServer().getServerInfo().getName()),
                  player.getUsername(), tabLatency(player)))));
      this.plugin.proxy().getAllPlayers().forEach(viewer -> reconcileViewer(viewer, roster));
    } finally {
      this.reconciling.set(false);
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
    Player target = this.plugin.proxy().getPlayer(targetId).orElse(null);
    if (target == null) {
      return false;
    }
    var existing = tabList.getEntry(targetId);
    if (existing.isPresent()) {
      existing.get().setDisplayName(displayName(state));
      existing.get().setLatency(state.latency());
      return true;
    }
    tabList.addEntry(tabList.buildEntry(
        target.getGameProfile(), displayName(state), state.latency(), 0));
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
    if (!this.reconcileQueued.compareAndSet(false, true)) {
      return;
    }
    this.plugin.proxy().getScheduler().buildTask(this.plugin, () -> {
          this.reconcileQueued.set(false);
          if (this.enabled) {
            reconcile();
          }
        })
        .delay(delay)
        .schedule();
  }

  private static Component displayName(EntryState state) {
    return Component.text("[" + state.serverName() + "] ", NamedTextColor.AQUA)
        .append(Component.text(state.username(), NamedTextColor.WHITE));
  }

  private String displayServerName(String serverName) {
    return this.serverNames.getOrDefault(serverName, serverName);
  }

  private Map<String, String> loadServerNames() {
    Path path = this.serverNamesPath;
    try {
      if (Files.notExists(path)) {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "# 跨服 TAB 子服名称映射；键为 velocity.toml 中的真实服务器名。\n"
            + "servers:\n  lobby: \"大厅\"\n  survival: \"生存服\"\n  minigames: \"小游戏\"\n",
            StandardCharsets.UTF_8);
      }
      Object parsed = new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
      if (!(parsed instanceof Map<?, ?> root) || !(root.get("servers") instanceof Map<?, ?> values)) {
        return Map.of();
      }
      Map<String, String> aliases = new LinkedHashMap<>();
      values.forEach((key, value) -> {
        if (key != null && value != null && !key.toString().isBlank() && !value.toString().isBlank()) {
          aliases.put(key.toString().trim(), value.toString().trim());
        }
      });
      this.serverNamesModifiedAt = Files.getLastModifiedTime(path).toMillis();
      this.serverNamesLoadValid = true;
      return Map.copyOf(aliases);
    } catch (IOException | RuntimeException error) {
      this.serverNamesLoadValid = false;
      rememberCurrentModificationTime();
      this.plugin.logger().warning("无法读取 cross-server-tab.yml，将使用真实子服名：" + error.getMessage());
      return Map.of();
    }
  }

  private void rememberCurrentModificationTime() {
    try {
      this.serverNamesModifiedAt = Files.getLastModifiedTime(this.serverNamesPath).toMillis();
    } catch (IOException ignored) {
      this.serverNamesModifiedAt = Long.MIN_VALUE;
    }
  }

  private void reloadServerNamesIfChanged() {
    try {
      long modifiedAt = Files.getLastModifiedTime(this.serverNamesPath).toMillis();
      if (modifiedAt == this.serverNamesModifiedAt) {
        return;
      }
      Map<String, String> loaded = loadServerNames();
      if (!this.serverNamesLoadValid) {
        return;
      }
      this.serverNames = loaded;
      this.plugin.logger().info("已热加载 cross-server-tab.yml（" + loaded.size() + " 个映射）");
    } catch (IOException error) {
      this.plugin.logger().warning("无法检查 cross-server-tab.yml，保留上一份有效配置：" + error.getMessage());
    }
  }

  private static int tabLatency(Player player) {
    long ping = player.getPing();
    return ping < 0 || ping > Integer.MAX_VALUE ? -1 : (int) ping;
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
      sentEntries.remove(event.getPlayer().getUniqueId());
      scheduleReconcile(Duration.ZERO);
    }
  }

  private record EntryState(String serverName, String username, int latency) {
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
