package io.github.addxiaoyi.starx.velocity.module.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.variable.StarxVariableService;
import java.time.ZoneId;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

final class PlayerListRendererTest {

  @Test
  void rendersMiniMessageAfterResolvingBuiltInVariables() {
    PlayerListRenderer renderer = new PlayerListRenderer(
        new StarxVariableService(ZoneId.of("Asia/Shanghai")));
    StarxConfig.PlayerListConfig config = new StarxConfig.PlayerListConfig(
        5,
        "<gold><bold>StarMC</bold></gold>\n你好，{starx_player}",
        "<gray>{starx_server} · 在线 {starx_online}</gray>");
    StarxVariableService.PlayerContext player =
        StarxVariableService.PlayerContext.guest("Alex", 8);

    PlayerListRenderer.Content content = renderer.render(config, player);
    PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    assertEquals("StarMC\n你好，Alex", plain.serialize(content.header()));
    assertEquals("未连接 · 在线 8", plain.serialize(content.footer()));
  }
}
