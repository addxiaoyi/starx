package io.github.addxiaoyi.starx.velocity.variable;

import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.velocity.identity.OfflineIdentityPolicy;
import io.github.addxiaoyi.starx.velocity.integration.TrustedIdentityProvider;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class StarxPlayerContextFactory {

  private final OfflineIdentityPolicy offlineIdentity;
  private final String prefixedIdentityName;
  private final TrustedIdentityProvider trustedIdentity;

  public StarxPlayerContextFactory(
      OfflineIdentityPolicy offlineIdentity,
      String prefixedIdentityName) {
    this(offlineIdentity, prefixedIdentityName, TrustedIdentityProvider.none());
  }

  public StarxPlayerContextFactory(
      OfflineIdentityPolicy offlineIdentity,
      String prefixedIdentityName,
      TrustedIdentityProvider trustedIdentity) {
    this.offlineIdentity = Objects.requireNonNull(offlineIdentity, "offlineIdentity");
    if (prefixedIdentityName == null || prefixedIdentityName.isBlank()) {
      throw new IllegalArgumentException("prefixedIdentityName cannot be blank");
    }
    this.prefixedIdentityName = prefixedIdentityName.trim();
    this.trustedIdentity = Objects.requireNonNull(trustedIdentity, "trustedIdentity");
  }

  public StarxVariableService.PlayerContext create(
      String username,
      boolean onlineMode,
      boolean requiresAuth,
      StarxUser user,
      PlayerBinding binding,
      String serverName,
      int onlinePlayers) {
    return this.create(null, username, onlineMode, requiresAuth, user, binding, serverName,
        onlinePlayers, 0, 0, 0,
        PlayerIdentityMetrics.from(user, binding, null, Map.of(), Instant.now()));
  }

  public StarxVariableService.PlayerContext create(
      String username,
      boolean onlineMode,
      boolean requiresAuth,
      StarxUser user,
      PlayerBinding binding,
      String serverName,
      int onlinePlayers,
      int networkMaxPlayers,
      int serverOnlinePlayers,
      int serverMaxPlayers) {
    return this.create(null, username, onlineMode, requiresAuth, user, binding, serverName,
        onlinePlayers, networkMaxPlayers, serverOnlinePlayers, serverMaxPlayers,
        PlayerIdentityMetrics.from(user, binding, null, Map.of(), Instant.now()));
  }

  public StarxVariableService.PlayerContext create(
      String username,
      boolean onlineMode,
      boolean requiresAuth,
      StarxUser user,
      PlayerBinding binding,
      String serverName,
      int onlinePlayers,
      int networkMaxPlayers,
      int serverOnlinePlayers,
      int serverMaxPlayers,
      PlayerIdentityMetrics metrics) {
    return this.create(null, username, onlineMode, requiresAuth, user, binding, serverName,
        onlinePlayers, networkMaxPlayers, serverOnlinePlayers, serverMaxPlayers, metrics);
  }

  public StarxVariableService.PlayerContext create(
      UUID playerId,
      String username,
      boolean onlineMode,
      boolean requiresAuth,
      StarxUser user,
      PlayerBinding binding,
      String serverName,
      int onlinePlayers,
      int networkMaxPlayers,
      int serverOnlinePlayers,
      int serverMaxPlayers,
      PlayerIdentityMetrics metrics) {
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(metrics, "metrics");
    StarxVariableService.AuthState authState = requiresAuth
        ? StarxVariableService.AuthState.AWAITING_PASSWORD
        : StarxVariableService.AuthState.AUTHENTICATED;
    boolean qqBound = binding != null && hasText(binding.qqId());
    boolean discordBound = binding != null && hasText(binding.discordId());
    TrustedIdentityProvider.ClientPlatform platform = playerId == null
        ? TrustedIdentityProvider.ClientPlatform.JAVA
        : this.trustedIdentity.platform(playerId);
    boolean bedrock = platform == TrustedIdentityProvider.ClientPlatform.BEDROCK;
    return new StarxVariableService.PlayerContext(
        username,
        authState,
        user != null,
        user != null && hasText(user.totpSecret()),
        user == null ? null : user.lastLoginAt(),
        loginSource(username, onlineMode, bedrock),
        qqBound,
        discordBound,
        metrics.playtimeSeconds(),
        user == null ? null : user.createdAt(),
        serverName,
        onlinePlayers,
        networkMaxPlayers,
        serverOnlinePlayers,
        serverMaxPlayers,
        metrics.serverFootprint(),
        metrics.reputation(),
        metrics.trustLevel(),
        platform.label(),
        bedrock);
  }

  private String loginSource(String username, boolean onlineMode, boolean bedrock) {
    if (bedrock) {
      return "基岩版 Floodgate";
    }
    if (onlineMode) {
      return "Java 正版账号";
    }
    return this.offlineIdentity.isPrefixed(username)
        ? this.prefixedIdentityName
        : "Java 离线账号";
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
