package io.github.addxiaoyi.starx.velocity.integration;

import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.UUID;

public final class FloodgateIntegrationModule
    implements VelocityModule, TrustedIdentityProvider {

  private static final String API_CLASS = "org.geysermc.floodgate.api.FloodgateApi";

  private final StarxVelocityPlugin plugin;
  private TrustedIdentityProvider provider = TrustedIdentityProvider.none();

  public FloodgateIntegrationModule(StarxVelocityPlugin plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  @Override
  public String name() {
    return "starx.integrations.floodgate";
  }

  @Override
  public void onEnable() {
    if (this.plugin.proxy().getPluginManager().getPlugin("floodgate").isEmpty()) {
      this.plugin.logger().info("未安装 Floodgate，离线玩家继续使用 Uworld 密码登录");
      return;
    }
    this.provider = discoverProvider();
    this.plugin.logger().info("已解锁 Floodgate：可信基岩玩家自动认证");
  }

  @Override
  public void onDisable() {
    this.provider = TrustedIdentityProvider.none();
  }

  @Override
  public boolean isTrusted(UUID playerId) {
    return this.provider.isTrusted(playerId);
  }

  private TrustedIdentityProvider discoverProvider() {
    try {
      Class<?> apiClass = Class.forName(API_CLASS);
      Object api = apiClass.getMethod("getInstance").invoke(null);
      return FloodgateIdentityProvider.fromApi(api);
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException error) {
      throw new IllegalStateException("Floodgate 已安装，但 API 不兼容", error);
    } catch (InvocationTargetException error) {
      throw new IllegalStateException("Floodgate API 初始化失败", error.getCause());
    }
  }
}
