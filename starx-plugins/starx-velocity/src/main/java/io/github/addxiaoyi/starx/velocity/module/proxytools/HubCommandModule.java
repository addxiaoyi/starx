package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class HubCommandModule implements VelocityModule {

  public static final String MODULE_ID = "starx.hub";

  private final StarxVelocityPlugin plugin;
  private final Config config;
  private final TransferCoordinator transfers = new TransferCoordinator(java.time.Duration.ofSeconds(10));

  public HubCommandModule(StarxVelocityPlugin plugin, Config config) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.config = Objects.requireNonNull(config, "config");
  }

  @Override
  public String name() {
    return MODULE_ID;
  }

  @Override
  public void onEnable() {
    if (!this.config.enabled()) {
      return;
    }
    ProxyServer proxy = this.plugin.proxy();
    try {
      proxy.getCommandManager().register("sxhub", (Command) new HubCommand(), "hub", "lobby");
    } catch (RuntimeException error) {
      proxy.getCommandManager().unregister("sxhub");
      throw error;
    }
  }

  @Override
  public void onDisable() {
    if (!this.config.enabled()) {
      return;
    }
    RuntimeException failure = null;
    try {
      this.plugin.proxy().getCommandManager().unregister("sxhub");
    } catch (RuntimeException error) {
      failure = error;
    }
    if (failure != null) {
      throw new IllegalStateException("Unable to unregister hub commands", failure);
    }
    this.transfers.clear();
  }

  public void sendToHub(Player player) {
    RegisteredServer hub = this.plugin.proxy().getServer(this.config.hubServerName()).orElse(null);
    if (hub == null) {
      player.sendMessage(Component.text("大厅服务器暂不可用。", NamedTextColor.RED));
      return;
    }
    if (player.getCurrentServer().map(connection ->
        connection.getServer().getServerInfo().getName().equals(this.config.hubServerName())).orElse(false)) {
      player.sendMessage(Component.text("你已经在大厅，无需重复转服。", NamedTextColor.GRAY));
      return;
    }
    if (this.transfers.isActive(player.getUniqueId())) {
      player.sendMessage(Component.text("正在前往大厅，请等待当前请求完成。", NamedTextColor.YELLOW));
      return;
    }
    player.sendMessage(Component.text("正在前往大厅…", NamedTextColor.YELLOW));
    try {
      this.transfers.transfer(player, hub, "hub")
          .whenComplete((result, error) -> {
            if (error == null && result != null
                && result.status() == TransferCoordinator.Status.DUPLICATE) {
              player.sendMessage(Component.text("正在前往大厅，请等待当前请求完成。", NamedTextColor.YELLOW));
              return;
            }
            if (error == null && result != null
                && result.status() == TransferCoordinator.Status.CANCELLED) {
              player.sendMessage(Component.text("大厅转服请求已取消，当前连接保持不变。", NamedTextColor.GRAY));
              return;
            }
            if (error != null || result == null || !result.successful()) {
              player.sendMessage(Component.text(
                  "前往大厅失败，当前连接保持不变，请稍后重试。", NamedTextColor.RED));
            }
          });
    } catch (RuntimeException error) {
      player.sendMessage(Component.text("前往大厅失败，当前连接保持不变。", NamedTextColor.RED));
      this.plugin.logger().fine("Unable to start hub transfer for " + player.getUniqueId());
    }
  }

  public interface Config {
    boolean enabled();

    String hubServerName();

    static Config defaultConfig() {
      return enabled("lobby");
    }

    static Config enabled(String hubServerName) {
      String target = Objects.requireNonNull(hubServerName, "hubServerName").trim();
      if (target.isEmpty()) {
        throw new IllegalArgumentException("hubServerName is blank");
      }
      return new Config() {
        @Override
        public boolean enabled() {
          return true;
        }

        @Override
        public String hubServerName() {
          return target;
        }
      };
    }
  }

  private final class HubCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
      CommandSource source = invocation.source();
      if (source instanceof Player player) {
        String[] args = invocation.arguments();
        if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
          boolean inHub = player.getCurrentServer().map(connection ->
              connection.getServer().getServerInfo().getName().equals(config.hubServerName())).orElse(false);
          player.sendMessage(Component.text(
              inHub
                  ? "大厅转服状态：已在大厅。"
                  : transfers.isActive(player.getUniqueId())
                  ? "大厅转服状态：正在连接（目标：" + config.hubServerName() + "）。"
                  : "大厅转服状态：当前没有进行中的请求。",
              NamedTextColor.GRAY));
          return;
        }
        if (args.length == 1 && "cancel".equalsIgnoreCase(args[0])) {
          boolean cancelled = transfers.cancel(player.getUniqueId());
          player.sendMessage(Component.text(
              cancelled ? "正在取消大厅转服请求…" : "当前没有可取消的大厅转服请求。",
              NamedTextColor.GRAY));
          return;
        }
        if (args.length > 0) {
          player.sendMessage(Component.text("用法：/sxhub、/sxhub status 或 /sxhub cancel。", NamedTextColor.YELLOW));
          return;
        }
        HubCommandModule.this.sendToHub(player);
      }
    }

    @Override
    public java.util.List<String> suggest(Invocation invocation) {
      String[] args = invocation.arguments();
      if (args.length > 1) return java.util.List.of();
      String prefix = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
      return java.util.List.of("status", "cancel").stream()
          .filter(option -> option.startsWith(prefix))
          .toList();
    }
  }
}
