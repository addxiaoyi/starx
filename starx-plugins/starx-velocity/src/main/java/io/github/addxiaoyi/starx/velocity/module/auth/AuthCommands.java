package io.github.addxiaoyi.starx.velocity.module.auth;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

public final class AuthCommands implements VelocityModule {
  private final StarxVelocityPlugin plugin;
  private final AuthService authService;
  private CommandMeta commandMeta;

  public AuthCommands(StarxVelocityPlugin plugin, AuthService authService) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.authService = Objects.requireNonNull(authService, "authService");
  }

  @Override
  public String name() {
    return "starx.auth-commands";
  }

  @Override
  public void onEnable() {
    this.commandMeta = this.plugin.proxy().getCommandManager().metaBuilder("sxsecure").build();
    this.plugin.proxy().getCommandManager().register(this.commandMeta, (Command) new SecurityHint());
  }

  @Override
  public void onDisable() {
    CommandMeta current = this.commandMeta;
    this.commandMeta = null;
    if (current != null) this.plugin.proxy().getCommandManager().unregister(current);
  }

  private final class SecurityHint implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
      CommandSource source = invocation.source();
      if (!(source instanceof Player player)) {
        source.sendMessage(Component.text("此命令仅限玩家使用", NamedTextColor.RED));
        return;
      }

      boolean enabled = AuthCommands.this.authService.isTotpEnabled(player.getUniqueId());
      source.sendMessage(Component.text(
          enabled ? "二步验证：已开启" : "二步验证：未开启",
          enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
      source.sendMessage(Component.text("点击打开 StarX 账号安全", NamedTextColor.AQUA)
          .clickEvent(ClickEvent.runCommand("/sx"))
          .hoverEvent(HoverEvent.showText(Component.text("密码和验证码将在铁砧界面输入"))));
      if (invocation.arguments().length > 0) {
        source.sendMessage(Component.text(
            "为避免敏感信息留在命令历史中，该命令不再接受参数。",
            NamedTextColor.GRAY));
      }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return true;
    }
  }
}
