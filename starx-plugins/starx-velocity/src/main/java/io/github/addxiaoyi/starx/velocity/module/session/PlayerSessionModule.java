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

/** Records player presence without changing connection decisions or UUIDs. */
public final class PlayerSessionModule implements VelocityModule {
  private final StarxVelocityPlugin plugin;
  private final JdbcPlayerSessionRepository sessions;
  private final Clock clock;
  private Listener listener;

  public PlayerSessionModule(StarxVelocityPlugin plugin, JdbcPlayerSessionRepository sessions) {
    this(plugin, sessions, Clock.systemUTC());
  }

  PlayerSessionModule(StarxVelocityPlugin plugin, JdbcPlayerSessionRepository sessions, Clock clock) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override public String name() { return "starx.player-sessions"; }

  @Override public void onEnable() {
    Listener currentListener = new Listener();
    this.listener = currentListener;
    plugin.proxy().getEventManager().register(plugin, currentListener);
  }

  @Override public void onDisable() {
    Listener currentListener = this.listener;
    this.listener = null;
    if (currentListener != null) {
      plugin.proxy().getEventManager().unregisterListener(plugin, currentListener);
    }
  }

  void onServerConnected(ServerConnectedEvent event) {
    Player player = event.getPlayer();
    String server = event.getServer().getServerInfo().getName();
    long now = clock.millis();
    sessions.activeSession(player.getUniqueId()).ifPresentOrElse(
        id -> sessions.transition(id, server, now),
        () -> sessions.start(player.getUniqueId(), server, now));
  }

  void onDisconnect(DisconnectEvent event) {
    sessions.activeSession(event.getPlayer().getUniqueId()).ifPresent(id ->
        sessions.finish(id, clock.millis(), DisconnectReason.NORMAL));
  }

  private final class Listener {
    @Subscribe public void onServerConnected(ServerConnectedEvent event) { PlayerSessionModule.this.onServerConnected(event); }
    @Subscribe public void onDisconnect(DisconnectEvent event) { PlayerSessionModule.this.onDisconnect(event); }
  }
}
