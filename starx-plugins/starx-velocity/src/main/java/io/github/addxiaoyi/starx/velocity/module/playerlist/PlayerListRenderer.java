package io.github.addxiaoyi.starx.velocity.module.playerlist;

import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.variable.StarxVariableService;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class PlayerListRenderer {

  private final StarxVariableService variables;
  private final MiniMessage miniMessage;

  public PlayerListRenderer(StarxVariableService variables) {
    this(variables, MiniMessage.miniMessage());
  }

  PlayerListRenderer(StarxVariableService variables, MiniMessage miniMessage) {
    this.variables = Objects.requireNonNull(variables, "variables");
    this.miniMessage = Objects.requireNonNull(miniMessage, "miniMessage");
  }

  public Content render(
      StarxConfig.PlayerListConfig config,
      StarxVariableService.PlayerContext player) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(player, "player");
    Component header = this.miniMessage.deserialize(
        this.variables.render(config.header(), player));
    Component footer = this.miniMessage.deserialize(
        this.variables.render(config.footer(), player));
    return new Content(header, footer);
  }

  public record Content(Component header, Component footer) {

    public Content {
      header = Objects.requireNonNull(header, "header");
      footer = Objects.requireNonNull(footer, "footer");
    }
  }
}
