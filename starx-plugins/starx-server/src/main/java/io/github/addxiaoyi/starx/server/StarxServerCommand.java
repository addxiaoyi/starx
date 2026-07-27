package io.github.addxiaoyi.starx.server;

import io.github.addxiaoyi.starx.api.compat.CompatibilityCheck;
import io.github.addxiaoyi.starx.api.compat.CompatibilityReport;
import io.github.addxiaoyi.starx.api.compat.CompatibilityStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

final class StarxServerCommand implements CommandExecutor {
  private final BackendBridgeSession session;
  private final CompatibilityReport compatibility;

  StarxServerCommand(BackendBridgeSession session, CompatibilityReport compatibility) {
    this.session = Objects.requireNonNull(session, "session");
    this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
  }

  @Override
  public boolean onCommand(
      CommandSender sender,
      Command command,
      String label,
      String[] args
  ) {
    String action = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
    if ("capabilities".equals(action)) {
      sender.sendMessage(Component.text(
          "StarX capabilities (" + this.session.platform() + "):",
          NamedTextColor.GOLD));
      for (String capability : new TreeSet<>(
          ServerCapabilities.forPlatform(this.session.platform()))) {
        sender.sendMessage(Component.text(" - " + capability, NamedTextColor.GRAY));
      }
      return true;
    }
    if ("doctor".equals(action)) {
      return this.sendDoctor(sender);
    }
    if ("skin".equals(action)) {
      return this.sendSkinProbe(sender, label, args);
    }
    if (!"status".equals(action)) {
      sender.sendMessage(Component.text(
          "用法：/" + label + " <status|doctor|capabilities|skin <uuid> <name>>",
          NamedTextColor.RED));
      return true;
    }

    Map<String, String> status = this.session.currentStatus();
    String lastContact = this.session.lastProxyContact()
        .map(Instant::toString)
        .orElse("not-seen");
    sender.sendMessage(Component.text(
        "StarX backend " + this.session.nodeId(), NamedTextColor.GOLD));
    sender.sendMessage(Component.text(
        "Platform: " + this.session.platform(), NamedTextColor.GRAY));
    sender.sendMessage(Component.text(
        "Execution: " + status.get("execution"), NamedTextColor.GRAY));
    sender.sendMessage(Component.text(
        "Players: " + status.getOrDefault("online", "?")
            + "/" + status.getOrDefault("max", "?"),
        NamedTextColor.GRAY));
    sender.sendMessage(Component.text(
        "Last proxy contact: " + lastContact, NamedTextColor.GRAY));
    return true;
  }

  private boolean sendDoctor(CommandSender sender) {
    sender.sendMessage(Component.text(
        "StarX compatibility: " + this.compatibility.overallStatus(),
        color(this.compatibility.overallStatus())));
    for (CompatibilityCheck check : this.compatibility.checks()) {
      sender.sendMessage(Component.text(
          " - " + check.component() + ": " + check.status()
              + " detected=" + check.detectedVersion()
              + " supported=" + check.supportedRange(),
          color(check.status())));
    }
    return true;
  }

  private static NamedTextColor color(CompatibilityStatus status) {
    return switch (status) {
      case SUPPORTED -> NamedTextColor.GREEN;
      case UNKNOWN -> NamedTextColor.YELLOW;
      case DEGRADED -> NamedTextColor.GOLD;
      case UNSUPPORTED -> NamedTextColor.RED;
    };
  }

  private boolean sendSkinProbe(CommandSender sender, String label, String[] args) {
    if (args.length != 3) {
      sender.sendMessage(Component.text(
          "用法：/" + label + " skin <uuid> <name>",
          NamedTextColor.RED));
      return true;
    }

    UUID uuid;
    try {
      uuid = UUID.fromString(args[1]);
    } catch (IllegalArgumentException error) {
      sender.sendMessage(Component.text("皮肤诊断失败：UUID 格式无效。", NamedTextColor.RED));
      return true;
    }

    Optional<BackendSkinProfile> profile = this.session.findSkin(uuid, args[2]);
    if (profile.isEmpty()) {
      sender.sendMessage(Component.text(
          "皮肤诊断：found=false uuid=" + uuid + " name=" + args[2],
          NamedTextColor.YELLOW));
      return true;
    }

    BackendSkinProfile skin = profile.get();
    sender.sendMessage(Component.text(
        "皮肤诊断：found=true provider=" + skin.provider()
            + " value-chars=" + skin.value().length()
            + " signature=" + !skin.signature().isBlank(),
        NamedTextColor.GREEN));
    return true;
  }
}
