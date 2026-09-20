package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

final class AuthLoginCard {
  private static final DateTimeFormatter TIME = DateTimeFormatter
      .ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.of("Asia/Shanghai"));

  private AuthLoginCard() {
  }

  static Component render(
      StarxUser user,
      String currentIp,
      String target,
      String bindingUrl,
      StarxConfig.AuthCardMessages messages
  ) {
    String account = user == null
        ? messages.firstLoginAccount()
        : user.premium() ? messages.premiumAccount() : messages.offlineAccount();
    String username = user == null ? messages.newPlayerName() : user.username();
    String uuid = user == null ? messages.registrationPendingTime() : user.uuid().toString();
    String lastLogin = user == null || user.lastLoginAt() == null
        ? messages.noHistory() : TIME.format(user.lastLoginAt());
    String lastIp = user == null || user.lastLoginIp() == null || user.lastLoginIp().isBlank()
        ? messages.noHistory() : user.lastLoginIp();
    String registeredAt = user == null || user.createdAt() == null
        ? messages.registrationPendingTime() : TIME.format(user.createdAt());
    long playtime = user == null || user.totalPlaytime() == null ? 0L : user.totalPlaytime();
    Component link = Component.text(messages.loginLinkText(), NamedTextColor.GOLD)
        .clickEvent(ClickEvent.openUrl(bindingUrl))
        .hoverEvent(HoverEvent.showText(Component.text(messages.loginLinkHover())));
    return renderCard(
        username,
        uuid,
        account,
        currentIp,
        lastIp,
        lastLogin,
        playtime,
        registeredAt,
        target,
        link,
        messages);
  }

  static Component renderRegistration(
      String username,
      UUID uuid,
      boolean premium,
      String currentIp,
      String target,
      String accountCenterUrl,
      StarxConfig.AuthCardMessages messages
  ) {
    Component link = Component.text(messages.registrationLinkText(), NamedTextColor.GOLD)
        .clickEvent(ClickEvent.openUrl(accountCenterUrl))
        .hoverEvent(HoverEvent.showText(Component.text(messages.registrationLinkHover())));
    return renderCard(
        username,
        uuid.toString(),
        premium ? messages.registrationPremiumAccount() : messages.registrationOfflineAccount(),
        currentIp,
        messages.registrationHistory(),
        messages.registrationHistory(),
        0L,
        messages.registrationPendingTime(),
        target,
        link,
        messages);
  }

  private static Component renderCard(
      String username,
      String uuid,
      String account,
      String currentIp,
      String lastIp,
      String lastLogin,
      long playtime,
      String registeredAt,
      String target,
      Component link,
      StarxConfig.AuthCardMessages messages
  ) {
    TextComponent card = Component.text("\n" + messages.title(), NamedTextColor.AQUA)
        .append(Component.newline())
        .append(Component.text(messages.playerPrefix() + username, NamedTextColor.WHITE))
        .append(Component.newline())
        .append(Component.text(messages.uuidPrefix() + uuid, NamedTextColor.GRAY))
        .append(Component.newline())
        .append(Component.text(messages.accountTypePrefix() + account, NamedTextColor.GRAY))
        .append(Component.newline())
        .append(Component.text(messages.currentIpPrefix() + currentIp, NamedTextColor.YELLOW))
        .append(Component.newline())
        .append(Component.text(messages.lastIpPrefix() + lastIp, NamedTextColor.DARK_GRAY))
        .append(Component.newline())
        .append(Component.text(messages.lastLoginPrefix() + lastLogin, NamedTextColor.GRAY))
        .append(Component.newline())
        .append(Component.text(
            messages.playtimePrefix() + formatPlaytime(playtime, messages),
            NamedTextColor.GREEN))
        .append(Component.newline())
        .append(Component.text(messages.registeredAtPrefix() + registeredAt, NamedTextColor.GRAY))
        .append(Component.newline())
        .append(Component.text(messages.targetPrefix() + target, NamedTextColor.GRAY))
        .append(Component.newline());
    return card.append(link);
  }

  static String formatPlaytime(long totalSeconds) {
    return formatPlaytime(totalSeconds, StarxConfig.AuthCardMessages.defaults());
  }

  static String formatPlaytime(
      long totalSeconds,
      StarxConfig.AuthCardMessages messages
  ) {
    long seconds = Math.max(0, totalSeconds);
    long hours = seconds / 3600;
    long minutes = seconds % 3600 / 60;
    if (hours > 0) {
      return hours + " " + messages.hourUnit() + " "
          + minutes + " " + messages.minuteUnit();
    }
    return minutes + " " + messages.minuteUnit();
  }
}
