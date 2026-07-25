package io.github.addxiaoyi.starx.server;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class StarxPlaceholderExpansion extends PlaceholderExpansion {

  private final BackendBridgeSession session;
  private final String version;

  public StarxPlaceholderExpansion(BackendBridgeSession session, String version) {
    this.session = Objects.requireNonNull(session, "session");
    this.version = Objects.requireNonNullElse(version, "unknown");
  }

  @Override
  public @NotNull String getIdentifier() {
    return "starx";
  }

  @Override
  public @NotNull String getAuthor() {
    return "addxiaoyi";
  }

  @Override
  public @NotNull String getVersion() {
    return this.version;
  }

  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
    Map<String, String> status = this.session.currentStatus();
    return switch (params.toLowerCase(Locale.ROOT)) {
      case "node" -> this.session.nodeId();
      case "platform" -> this.session.platform().name().toLowerCase(Locale.ROOT);
      case "execution" -> this.session.platform().executionModel();
      case "capabilities" -> status.getOrDefault("capabilities", "");
      case "online" -> status.getOrDefault("online", "0");
      case "max" -> status.getOrDefault("max", "0");
      case "proxy_status" -> this.session.lastProxyContact().isPresent() ? "已连接" : "未连接";
      case "player" -> player == null ? "" : Objects.requireNonNullElse(player.getName(), "");
      default -> null;
    };
  }
}
