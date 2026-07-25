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
      proxy.getCommandManager().register(
          proxy.getCommandManager().metaBuilder("sxhub").build(),
          (Command) new HubCommand());
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
  }

  public void sendToHub(Player player) {
    RegisteredServer hub = this.plugin.proxy().getServer(this.config.hubServerName()).orElse(null);
    if (hub == null) {
      player.sendMessage(Component.text("大厅服务器暂不可用。", NamedTextColor.RED));
      return;
    }
    player.createConnectionRequest(hub).connect();
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
        HubCommandModule.this.sendToHub(player);
      }
    }
  }
}
