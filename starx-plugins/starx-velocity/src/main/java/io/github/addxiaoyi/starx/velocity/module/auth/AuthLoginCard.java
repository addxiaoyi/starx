package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

final class AuthLoginCard {
  private static final DateTimeFormatter TIME = DateTimeFormatter
      .ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.of("Asia/Shanghai"));

  private AuthLoginCard() {
  }

  static Component render(StarxUser user, String currentIp, String target, String bindingUrl) {
    String account = user == null ? "首次登录" : user.premium() ? "正版账号" : "离线账号";
    String uuid = user == null ? "注册后生成" : user.uuid().toString();
    String lastLogin = user == null || user.lastLoginAt() == null
        ? "无历史记录" : TIME.format(user.lastLoginAt());
    String lastIp = user == null || user.lastLoginIp() == null || user.lastLoginIp().isBlank()
        ? "无历史记录" : user.lastLoginIp();
    long playtime = user == null || user.totalPlaytime() == null ? 0L : user.totalPlaytime();

    Component card = Component.text("\n✦ StarMC 安全登录中心 ✦", NamedTextColor.AQUA)
        .append(Component.newline())
        .append(Component.text("玩家：" + (user == null ? "新玩家" : user.username()), NamedTextColor.WHITE))
        .append(Component.newline())
        .append(Component.text("玩家 UUID：" + uuid, NamedTextColor.GRAY))
        .append(Component.newline())
        .append(Component.text("账号类型：" + account, NamedTextColor.GRAY))
        .append(Component.newline())
        .append(Component.text("当前 IP：" + currentIp, NamedTextColor.YELLOW))
        .append(Component.newline())
        .append(Component.text("上次 IP：" + lastIp, NamedTextColor.DARK_GRAY))
        .append(Component.newline())
        .append(Component.text("上次登录：" + lastLogin, NamedTextColor.GRAY))
        .append(Component.newline())
        .append(Component.text("累计游玩：" + formatPlaytime(playtime), NamedTextColor.GREEN))
        .append(Component.newline())
        .append(Component.text("认证目标：" + target, NamedTextColor.GRAY))
        .append(Component.newline());
    Component link = Component.text("[点击打开 StarX 账号绑定 · 绑定后免密登录]", NamedTextColor.GOLD)
        .clickEvent(ClickEvent.openUrl(bindingUrl))
        .hoverEvent(HoverEvent.showText(Component.text("打开安全绑定页面（5 分钟内有效）")));
    return card.append(link);
  }

  static String formatPlaytime(long totalSeconds) {
    long seconds = Math.max(0, totalSeconds);
    long hours = seconds / 3600;
    long minutes = seconds % 3600 / 60;
    if (hours > 0) {
      return hours + " 小时 " + minutes + " 分钟";
    }
    return minutes + " 分钟";
  }
}
