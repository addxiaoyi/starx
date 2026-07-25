package io.github.addxiaoyi.starx.velocity.module.playerlist;

import com.velocitypowered.api.event.Subscribe;
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
import java.util.Objects;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;

public final class PlayerListModule implements VelocityModule {

  private final StarxVelocityPlugin plugin;
  private final JdbcUserRepository users;
  private final JdbcBindingRepository bindings;
  private final JdbcPlayerSessionRepository sessions;
  private final AuthModule authentication;
  private final StarxConfig.PlayerListConfig config;
  private final PlayerListRenderer renderer;
  private final StarxPlayerContextFactory contextFactory;

  private Listener listener;
  private ScheduledTask refreshTask;

  public PlayerListModule(
      StarxVelocityPlugin plugin,
      JdbcUserRepository users,
      JdbcBindingRepository bindings,
      JdbcPlayerSessionRepository sessions,
      AuthModule authentication,
      StarxConfig.PlayerListConfig config,
      PlayerListRenderer renderer,
      StarxPlayerContextFactory contextFactory) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.users = Objects.requireNonNull(users, "users");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.authentication = Objects.requireNonNull(authentication, "authentication");
    this.config = Objects.requireNonNull(config, "config");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
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
    this.plugin.proxy().getAllPlayers().forEach(player ->
        player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty()));
  }

  private void refreshAll() {
    this.plugin.proxy().getAllPlayers().forEach(this::refreshSafely);
  }

  private void refreshSafely(Player player) {
    try {
      StarxVariableService.PlayerContext context = this.contextFor(player);
      PlayerListRenderer.Content content = this.renderer.render(this.config, context);
      player.sendPlayerListHeaderAndFooter(content.header(), content.footer());
    } catch (RuntimeException error) {
      this.plugin.logger().log(
          Level.WARNING,
          "无法刷新玩家 " + player.getUsername() + " 的内置玩家列表",
          error);
    }
  }

  public StarxVariableService.PlayerContext contextFor(Player player) {
    Objects.requireNonNull(player, "player");
    StarxUser user = this.users.findFullByUuid(player.getUniqueId()).orElse(null);
    PlayerBinding binding = this.bindings.findByPlayer(player.getUniqueId()).orElse(null);
    String serverName = player.getCurrentServer()
        .map(connection -> connection.getServerInfo().getName())
        .orElse(null);
    int serverOnlinePlayers = player.getCurrentServer()
        .map(connection -> connection.getServer().getPlayersConnected().size())
        .orElse(0);
    PlayerIdentityMetrics metrics = PlayerIdentityMetrics.from(
        user,
        binding,
        this.sessions.summary(player.getUniqueId()).orElse(null),
        this.sessions.playtimeByServer(player.getUniqueId()),
        Instant.now());
    return this.contextFactory.create(
        player.getUniqueId(),
        player.getUsername(),
        player.isOnlineMode(),
        this.authentication.requiresAuth(player),
        user,
        binding,
        serverName,
        this.plugin.proxy().getPlayerCount(),
        this.plugin.proxy().getConfiguration().getShowMaxPlayers(),
        serverOnlinePlayers,
        0,
        metrics);
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
  }
}
