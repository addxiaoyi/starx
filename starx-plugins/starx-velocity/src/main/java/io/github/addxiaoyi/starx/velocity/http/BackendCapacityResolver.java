package io.github.addxiaoyi.starx.velocity.http;

import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

final class BackendCapacityResolver {

  private static final Duration MAX_AGE = Duration.ofMinutes(5);

  private BackendCapacityResolver() {
  }

  static int resolve(
      BackendNodeRegistry registry,
      String serverName,
      Instant now,
      int probedCapacity
  ) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(serverName, "serverName");
    Objects.requireNonNull(now, "now");

    var node = registry.find(serverName).orElse(null);
    if (node == null) {
      return Math.max(0, probedCapacity);
    }
    if (registry.admissionWeight(serverName) == 0) {
      return 0;
    }
    if (node.isStale(now, MAX_AGE)) {
      return Math.max(0, probedCapacity);
    }
    int capacity = node.maxPlayers();
    return capacity >= 0 ? capacity : Math.max(0, probedCapacity);
  }
}
