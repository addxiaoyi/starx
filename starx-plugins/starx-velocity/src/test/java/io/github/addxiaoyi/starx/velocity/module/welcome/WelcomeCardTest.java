package io.github.addxiaoyi.starx.velocity.module.welcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

final class WelcomeCardTest {

  @Test
  void firstLoginUsesStableUnframedRows() {
    Component card = WelcomeCard.firstLogin("add");
    String text = PlainTextComponentSerializer.plainText().serialize(card);

    assertFalse(text.matches("(?s).*[╭╮╰╯├┤│─].*"));
    assertEquals(List.of(
        "",
        "✦ add",
        "  首次登录 · 欢迎加入 StarMC",
        "",
        "  完善账号安全，登录会更快捷",
        "  › 绑定邮箱   保护账号与找回",
        "  › 开启 2FA   阻止未授权登录"), text.lines().toList());
  }

  @Test
  void securityActionsRemainClickableOnTheirOwnRows() {
    Component card = WelcomeCard.firstLogin("add");
    List<Component> actions = card.children().stream()
        .filter(child -> child.clickEvent() != null)
        .toList();

    assertEquals(2, actions.size());
    assertEquals(ClickEvent.Action.RUN_COMMAND, actions.get(0).clickEvent().action());
    assertEquals("/sx", actions.get(0).clickEvent().value());
    assertEquals("/sx", actions.get(1).clickEvent().value());
    assertTrue(actions.stream().allMatch(action -> action.hoverEvent() != null));
  }
}
