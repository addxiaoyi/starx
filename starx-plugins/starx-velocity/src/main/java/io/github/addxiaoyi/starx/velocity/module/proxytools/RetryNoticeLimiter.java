package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Advances the cooldown only when a retry notice is actually admitted. */
final class RetryNoticeLimiter {
  private static final long COOLDOWN_NANOS = Duration.ofSeconds(15).toNanos();
  private final Map<UUID, Long> sentAt = new HashMap<>();

  synchronized boolean allow(UUID playerId, long now) {
    Long previous = this.sentAt.get(playerId);
    if (previous != null && now - previous < COOLDOWN_NANOS) return false;
    this.sentAt.put(playerId, now);
    return true;
  }

  synchronized void remove(UUID playerId) {
    this.sentAt.remove(playerId);
  }

  synchronized void clear() {
    this.sentAt.clear();
  }
}
