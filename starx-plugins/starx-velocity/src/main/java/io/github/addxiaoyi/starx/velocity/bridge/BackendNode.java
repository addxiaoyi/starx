package io.github.addxiaoyi.starx.velocity.bridge;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record BackendNode(
    String registeredServer,
    String declaredNodeId,
    PlatformKind platform,
    Set<String> capabilities,
    Map<String, String> status,
    Instant lastSeen
) {

  public BackendNode {
    registeredServer = requireText(registeredServer, "registeredServer");
    declaredNodeId = requireText(declaredNodeId, "declaredNodeId");
    platform = Objects.requireNonNull(platform, "platform");
    capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    status = Map.copyOf(Objects.requireNonNull(status, "status"));
    lastSeen = Objects.requireNonNull(lastSeen, "lastSeen");
  }

  public int onlinePlayers() {
    return parseCount(this.status.get("online"));
  }

  public int maxPlayers() {
    return parseCount(this.status.get("max"));
  }

  public boolean isStale(Instant now, Duration maxAge) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(maxAge, "maxAge");
    if (maxAge.isNegative() || maxAge.isZero()) {
      throw new IllegalArgumentException("maxAge must be positive");
    }
    return now.isAfter(this.lastSeen.plus(maxAge));
  }

  private static int parseCount(String value) {
    if (value == null) {
      return -1;
    }
    try {
      int count = Integer.parseInt(value);
      return count < 0 ? -1 : count;
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
