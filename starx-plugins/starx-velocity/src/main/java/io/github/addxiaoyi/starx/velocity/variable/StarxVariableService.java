package io.github.addxiaoyi.starx.velocity.variable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StarxVariableService {

  private static final Pattern VARIABLE = Pattern.compile(
      "%([a-zA-Z0-9_]+)%|\\{([a-zA-Z0-9_]+)}");
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE);
  private static final Set<String> KEYS = Set.of(
      "starx_player", "starx_auth_status", "starx_registered",
      "starx_2fa_enabled", "starx_last_login", "starx_login_source",
      "starx_client_platform", "starx_bedrock",
      "starx_bind_qq", "starx_bind_discord", "starx_playtime",
      "starx_first_join", "starx_server", "starx_online",
      "starx_network_online", "starx_network_max", "starx_server_online",
      "starx_server_max", "starx_playtime_total", "starx_server_footprint",
      "starx_reputation", "starx_trust_level", "starx_servers");

  private final ZoneId zone;

  public StarxVariableService(ZoneId zone) {
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  public String render(String template, PlayerContext player) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(player, "player");
    Matcher matcher = VARIABLE.matcher(template);
    StringBuilder rendered = new StringBuilder(template.length() + 32);
    while (matcher.find()) {
      String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
      String value = knownValue(key, player);
      matcher.appendReplacement(rendered, Matcher.quoteReplacement(
          value != null ? value : matcher.group()));
    }
    matcher.appendTail(rendered);
    return rendered.toString();
  }

  public String resolve(String key, PlayerContext player) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(player, "player");
    String normalized = normalize(key);
    String value = knownValue(normalized, player);
    return value != null ? value : "{" + normalized + "}";
  }

  public Set<String> keys() {
    return KEYS;
  }

  public Set<String> referencedKeys(String... templates) {
    Objects.requireNonNull(templates, "templates");
    Set<String> referenced = new java.util.HashSet<>();
    for (String template : templates) {
      if (template == null || template.isBlank()) {
        continue;
      }
      Matcher matcher = VARIABLE.matcher(template);
      while (matcher.find()) {
        String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        String normalized = normalize(key);
        if (KEYS.contains(normalized)) {
          referenced.add(normalized);
        }
      }
    }
    return Set.copyOf(referenced);
  }

  private String knownValue(String key, PlayerContext player) {
    return switch (normalize(key)) {
      case "starx_player" -> player.playerName();
      case "starx_auth_status" -> player.authState().label();
      case "starx_registered" -> yesNo(player.registered());
      case "starx_2fa_enabled" -> player.totpEnabled() ? "已开启" : "未开启";
      case "starx_last_login" -> dateTime(player.lastLogin());
      case "starx_login_source" -> player.loginSource();
      case "starx_client_platform" -> player.clientPlatform();
      case "starx_bedrock" -> yesNo(player.bedrock());
      case "starx_bind_qq" -> binding(player.qqBound());
      case "starx_bind_discord" -> binding(player.discordBound());
      case "starx_playtime" -> playtime(player.playtimeSeconds());
      case "starx_first_join" -> dateTime(player.firstJoin());
      case "starx_server" -> blankTo(player.serverName(), "未连接");
      case "starx_online" -> Integer.toString(player.onlinePlayers());
      case "starx_network_online" -> Integer.toString(player.onlinePlayers());
      case "starx_network_max" -> Integer.toString(player.networkMaxPlayers());
      case "starx_server_online" -> Integer.toString(player.serverOnlinePlayers());
      case "starx_server_max" -> Integer.toString(player.serverMaxPlayers());
      case "starx_servers" -> player.onlineServers();
      case "starx_playtime_total" -> playtime(player.playtimeSeconds());
      case "starx_server_footprint" -> Integer.toString(player.serverFootprint());
      case "starx_reputation" -> Integer.toString(player.reputation());
      case "starx_trust_level" -> player.trustLevel();
      default -> null;
    };
  }

  private String dateTime(Instant instant) {
    return instant == null ? "从未" : DATE_TIME.withZone(this.zone).format(instant);
  }

  private static String playtime(long seconds) {
    long safeSeconds = Math.max(0, seconds);
    long hours = safeSeconds / 3600;
    long minutes = safeSeconds % 3600 / 60;
    return hours > 0 ? hours + " 小时 " + minutes + " 分钟" : minutes + " 分钟";
  }

  private static String normalize(String key) {
    String normalized = key.trim();
    if (normalized.length() > 2
        && ((normalized.startsWith("{") && normalized.endsWith("}"))
        || (normalized.startsWith("%") && normalized.endsWith("%")))) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private static String yesNo(boolean value) {
    return value ? "是" : "否";
  }

  private static String binding(boolean bound) {
    return bound ? "已绑定" : "未绑定";
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  public enum AuthState {
    AUTHENTICATED("已登录"),
    AWAITING_PASSWORD("待登录"),
    AWAITING_TOTP("待验证");

    private final String label;

    AuthState(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }
  }

  public record PlayerContext(
      String playerName,
      AuthState authState,
      boolean registered,
      boolean totpEnabled,
      Instant lastLogin,
      String loginSource,
      boolean qqBound,
      boolean discordBound,
      long playtimeSeconds,
      Instant firstJoin,
      String serverName,
      int onlinePlayers,
      int networkMaxPlayers,
      int serverOnlinePlayers,
      int serverMaxPlayers,
      int serverFootprint,
      int reputation,
      String trustLevel,
      String clientPlatform,
      boolean bedrock,
      String onlineServers) {

    public PlayerContext {
      playerName = Objects.requireNonNull(playerName, "playerName");
      authState = Objects.requireNonNull(authState, "authState");
      loginSource = blankTo(loginSource, "未知来源");
      clientPlatform = blankTo(clientPlatform, "Java版");
      onlineServers = blankTo(onlineServers, "暂无在线子服");
      if (onlinePlayers < 0 || networkMaxPlayers < 0
          || serverOnlinePlayers < 0 || serverMaxPlayers < 0 || serverFootprint < 0) {
        throw new IllegalArgumentException("player counts and footprint cannnot be negative");
      }
      if (reputation < 0 || reputation > 100) {
        throw new IllegalArgumentException("reputation must be between 0 and 100");
      }
      trustLevel = blankTo(trustLevel, "未评级");
    }

    public PlayerContext(
        String playerName,
        AuthState authState,
        boolean registered,
        boolean totpEnabled,
        Instant lastLogin,
        String loginSource,
        boolean qqBound,
        boolean discordBound,
        long playtimeSeconds,
        Instant firstJoin,
        String serverName,
        int onlinePlayers,
        int networkMaxPlayers,
        int serverOnlinePlayers,
        int serverMaxPlayers,
        int serverFootprint,
        int reputation,
        String trustLevel) {
      this(playerName, authState, registered, totpEnabled, lastLogin, loginSource,
          qqBound, discordBound, playtimeSeconds, firstJoin, serverName, onlinePlayers,
          networkMaxPlayers, serverOnlinePlayers, serverMaxPlayers, serverFootprint,
          reputation, trustLevel, "Java版", false, "暂无在线子服");
    }

    public PlayerContext(
        String playerName,
        AuthState authState,
        boolean registered,
        boolean totpEnabled,
        Instant lastLogin,
        String loginSource,
        boolean qqBound,
        boolean discordBound,
        long playtimeSeconds,
        Instant firstJoin,
        String serverName,
        int onlinePlayers,
        int networkMaxPlayers,
        int serverOnlinePlayers,
        int serverMaxPlayers) {
      this(playerName, authState, registered, totpEnabled, lastLogin, loginSource,
          qqBound, discordBound, playtimeSeconds, firstJoin, serverName, onlinePlayers,
          networkMaxPlayers, serverOnlinePlayers, serverMaxPlayers, 0, 0, "未评级");
    }

    public PlayerContext(
        String playerName,
        AuthState authState,
        boolean registered,
        boolean totpEnabled,
        Instant lastLogin,
        String loginSource,
        boolean qqBound,
        boolean discordBound,
        long playtimeSeconds,
        Instant firstJoin,
        String serverName,
        int onlinePlayers) {
      this(playerName, authState, registered, totpEnabled, lastLogin, loginSource,
          qqBound, discordBound, playtimeSeconds, firstJoin, serverName, onlinePlayers,
          0, 0, 0);
    }

    public static PlayerContext guest(String playerName, int onlinePlayers) {
      return new PlayerContext(
          playerName,
          AuthState.AWAITING_PASSWORD,
          false,
          false,
          null,
          "Java 离线账号",
          false,
          false,
          0,
          null,
          null,
          onlinePlayers,
          0,
          0,
          0,
          0,
          0,
          "未评级", "Java版", false, "暂无在线子服");
    }
  }
}
