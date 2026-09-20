package io.github.addxiaoyi.starx.velocity.module.welcome;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

final class WelcomeCard {
  private static final TextColor BRAND = TextColor.color(139, 92, 246);
  private static final TextColor TEXT = TextColor.color(226, 232, 240);
  private static final TextColor MUTED = TextColor.color(148, 163, 184);
  private static final TextColor ACTION = TextColor.color(96, 165, 250);

  private WelcomeCard() {
  }

  static Component firstLogin(String username) {
    return build(username, "首次登录", "欢迎加入 StarMC", List.of(), true, true);
  }

  static Component account(
      String username,
      boolean returning,
      List<Fact> facts,
      boolean needsEmail,
      boolean needs2fa) {
    String state = returning ? "欢迎回来" : "首次登录";
    String message = returning ? "很高兴再次见到你" : "欢迎加入 StarMC";
    return build(username, state, message, facts, needsEmail, needs2fa);
  }

  private static Component build(
      String username,
      String state,
      String message,
      List<Fact> facts,
      boolean needsEmail,
      boolean needs2fa) {
    TextComponent.Builder card = Component.text()
        .append(Component.newline())
        .append(Component.text("✦ ", BRAND))
        .append(Component.text(username, TEXT, TextDecoration.BOLD))
        .append(Component.newline())
        .append(Component.text("  " + state + " · " + message, BRAND))
        .append(Component.newline());

    for (Fact fact : facts) {
      card.append(Component.text(fact.label() + "  ", MUTED))
          .append(Component.text(fact.value(), TEXT))
          .append(Component.newline());
    }

    if (needsEmail || needs2fa) {
      card.append(Component.newline());
      card.append(Component.text("  完善账号安全，登录会更快捷", MUTED))
          .append(Component.newline());
    }
    if (needsEmail) {
      card.append(action(
          "  › 绑定邮箱   保护账号与找回",
          "/sx",
          "点击打开邮箱绑定界面"))
          .append(Component.newline());
    }
    if (needs2fa) {
      card.append(action(
          "  › 开启 2FA   阻止未授权登录",
          "/sx",
          "点击打开二步验证界面"))
          .append(Component.newline());
    }
    return card.build();
  }

  private static Component action(String label, String command, String hint) {
    return Component.text(label, ACTION, TextDecoration.BOLD)
        .clickEvent(ClickEvent.runCommand(command))
        .hoverEvent(HoverEvent.showText(Component.text(hint, TEXT)));
  }

  record Fact(String label, String value) {
  }
}
