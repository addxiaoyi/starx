package io.github.addxiaoyi.starx.velocity.integration;

import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.module.playerlist.PlayerListModule;
import io.github.addxiaoyi.starx.velocity.variable.StarxVariableService;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class TabIntegrationModule implements VelocityModule {

  private static final String API_CLASS = "me.neznamy.tab.api.TabAPI";
  private static final Duration CONTEXT_CACHE_TTL = Duration.ofSeconds(1);

  private final StarxVelocityPlugin plugin;
  private final PlayerListModule playerList;
  private final StarxVariableService variables;
  private final Map<UUID, CachedContext> contextCache = new ConcurrentHashMap<>();
  private TabPlaceholderRegistrar registrar;

  public TabIntegrationModule(
      StarxVelocityPlugin plugin,
      PlayerListModule playerList,
      StarxVariableService variables) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.playerList = Objects.requireNonNull(playerList, "playerList");
    this.variables = Objects.requireNonNull(variables, "variables");
  }

  @Override
  public String name() {
    return "starx.integrations.tab";
  }

  @Override
  public void onEnable() {
    if (this.plugin.proxy().getPluginManager().getPlugin("tab").isEmpty()) {
      this.plugin.logger().info("未安装 TAB，继续使用 StarX 内置玩家列表");
      return;
    }
    Object placeholderManager = discoverPlaceholderManager();
    this.registrar = new TabPlaceholderRegistrar(
        placeholderManager,
        this.variables,
        this::contextFor,
        1_000);
    this.registrar.registerAll();
    this.plugin.logger().info("已解锁 TAB：注册 " + this.variables.keys().size() + " 个 StarX 变量");
  }

  @Override
  public void onDisable() {
    TabPlaceholderRegistrar current = this.registrar;
    this.registrar = null;
    this.contextCache.clear();
    if (current != null) {
      current.unregisterAll();
    }
  }

  private Optional<StarxVariableService.PlayerContext> contextFor(UUID playerId) {
    Instant now = Instant.now();
    CachedContext cached = this.contextCache.get(playerId);
    if (cached != null && now.isBefore(cached.expiresAt())) {
      return Optional.of(cached.context());
    }
    return this.plugin.proxy().getPlayer(playerId).flatMap(player -> {
      try {
        StarxVariableService.PlayerContext context = this.playerList.contextFor(player);
        this.contextCache.put(playerId, new CachedContext(context, now.plus(CONTEXT_CACHE_TTL)));
        return Optional.of(context);
      } catch (RuntimeException error) {
        this.plugin.logger().log(
            Level.WARNING,
            "无法为 TAB 解析玩家 " + player.getUsername() + " 的 StarX 变量",
            error);
        return Optional.empty();
      }
    });
  }

  private Object discoverPlaceholderManager() {
    try {
      Class<?> apiClass = Class.forName(API_CLASS);
      Object api = apiClass.getMethod("getInstance").invoke(null);
      return apiClass.getMethod("getPlaceholderManager").invoke(api);
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException error) {
      throw new IllegalStateException("TAB 已安装，但 API 不兼容", error);
    } catch (InvocationTargetException error) {
      throw new IllegalStateException("TAB API 初始化失败", error.getCause());
    }
  }

  private record CachedContext(
      StarxVariableService.PlayerContext context,
      Instant expiresAt) {
  }
}
