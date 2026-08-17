package io.github.addxiaoyi.starx.velocity.bridge;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class PendingSkinRequests {
  static final Duration TTL = Duration.ofMinutes(2);

  private final ConcurrentMap<UUID, Pending> pending = new ConcurrentHashMap<>();

  void register(UUID playerUuid, String correlationId, Instant now) {
    Objects.requireNonNull(playerUuid, "playerUuid");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(now, "now");
    this.pending.put(playerUuid, new Pending(correlationId, now));
    purge(now);
  }

  boolean accept(BridgeMessage message, Instant now) {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(now, "now");
    UUID playerUuid = playerUuid(message);
    if (playerUuid == null) {
      return false;
    }
    Pending current = this.pending.get(playerUuid);
    if (current == null || current.expiresAt().compareTo(now) <= 0
        || !current.correlationId().equals(message.correlationId())) {
      purge(now);
      return false;
    }
    return this.pending.remove(playerUuid, current);
  }

  void cancel(UUID playerUuid, String correlationId) {
    Objects.requireNonNull(playerUuid, "playerUuid");
    Objects.requireNonNull(correlationId, "correlationId");
    this.pending.computeIfPresent(playerUuid, (ignored, current) ->
        current.correlationId().equals(correlationId) ? null : current);
  }

  void clear() {
    this.pending.clear();
  }

  private void purge(Instant now) {
    this.pending.entrySet().removeIf(entry -> entry.getValue().expiresAt().compareTo(now) <= 0);
  }

  private static UUID playerUuid(BridgeMessage message) {
    String raw = message.attributes().getOrDefault("uuid", "").trim();
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private record Pending(String correlationId, Instant createdAt) {
    private Instant expiresAt() {
      return this.createdAt.plus(TTL);
    }
  }
}
