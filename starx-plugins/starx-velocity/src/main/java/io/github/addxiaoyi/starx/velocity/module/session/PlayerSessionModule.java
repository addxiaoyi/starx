package io.github.addxiaoyi.starx.velocity.module.session;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.common.session.DisconnectReason;
import io.github.addxiaoyi.starx.common.session.JdbcPlayerSessionRepository;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Records player presence without changing connection decisions or UUIDs. */
public final class PlayerSessionModule implements VelocityModule {
  private final StarxVelocityPlugin plugin;
  private final JdbcPlayerSessionRepository sessions;
  private final Clock clock;
  private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;
  private final java.util.concurrent.ConcurrentMap<UUID, Player> activePlayers =
      new ConcurrentHashMap<>();
  private Listener listener;

  public PlayerSessionModule(StarxVelocityPlugin plugin, JdbcPlayerSessionRepository sessions) {
    this(plugin, sessions, Clock.systemUTC(), uuid -> Set.of(uuid));
  }

  PlayerSessionModule(StarxVelocityPlugin plugin, JdbcPlayerSessionRepository sessions, Clock clock) {
    this(plugin, sessions, clock, uuid -> Set.of(uuid));
  }

  public PlayerSessionModule(
      StarxVelocityPlugin plugin,
      JdbcPlayerSessionRepository sessions,
      Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
    this(plugin, sessions, Clock.systemUTC(), knownMinecraftUuidsResolver);
  }

  PlayerSessionModule(
      StarxVelocityPlugin plugin,
      JdbcPlayerSessionRepository sessions,
      Clock clock,
      Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.knownMinecraftUuidsResolver = Objects.requireNonNull(
        knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
  }

  @Override public String name() { return "starx.player-sessions"; }

  @Override public void onEnable() {
    sessions.finishActive(clock.millis(), DisconnectReason.PROXY_STOP);
    Listener currentListener = new Listener();
    this.listener = currentListener;
    plugin.proxy().getEventManager().register(plugin, currentListener);
    long now = clock.millis();
    plugin.proxy().getAllPlayers().forEach(player -> {
      this.activePlayers.put(player.getUniqueId(), player);
      player.getCurrentServer().ifPresent(server ->
          sessions.start(player.getUniqueId(), server.getServerInfo().getName(), now));
    });
  }

  @Override public void onDisable() {
    Listener currentListener = this.listener;
    this.listener = null;
    if (currentListener != null) {
      plugin.proxy().getEventManager().unregisterListener(plugin, currentListener);
    }
    this.activePlayers.clear();
  }

  void onServerConnected(ServerConnectedEvent event) {
    Player player = event.getPlayer();
    Player previous = this.activePlayers.put(player.getUniqueId(), player);
    String server = event.getServer().getServerInfo().getName();
    long now = clock.millis();
    if (previous != null && previous != player) {
      sessions.activeSession(this.knownMinecraftUuidsResolver.apply(player.getUniqueId())).ifPresent(id ->
          sessions.finish(id, now, DisconnectReason.NORMAL));
      sessions.start(player.getUniqueId(), server, now);
      return;
    }
    sessions.activeSession(this.knownMinecraftUuidsResolver.apply(player.getUniqueId())).ifPresentOrElse(
        id -> sessions.transition(id, server, now),
        () -> sessions.start(player.getUniqueId(), server, now));
  }

  void onDisconnect(DisconnectEvent event) {
    Player player = event.getPlayer();
    UUID playerId = player.getUniqueId();
    if (!this.detachActivePlayer(playerId, player)) {
      return;
    }
    sessions.activeSession(this.knownMinecraftUuidsResolver.apply(playerId)).ifPresent(id ->
        sessions.finish(id, clock.millis(), DisconnectReason.NORMAL));
  }

  private boolean detachActivePlayer(UUID playerId, Player player) {
    java.util.concurrent.atomic.AtomicBoolean removed =
        new java.util.concurrent.atomic.AtomicBoolean();
    this.activePlayers.compute(playerId, (ignored, current) -> {
      if (current == player) {
        removed.set(true);
        return null;
      }
      return current;
    });
    return removed.get();
  }

  private final class Listener {
    @Subscribe public void onServerConnected(ServerConnectedEvent event) { PlayerSessionModule.this.onServerConnected(event); }
    @Subscribe public void onDisconnect(DisconnectEvent event) { PlayerSessionModule.this.onDisconnect(event); }
  }
}
