package io.github.addxiaoyi.starx.velocity.module.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class LoginAttemptLimiter {
  private final int capacity;
  private final int maxAttempts;
  private final Duration resetAfter;
  private final Map<UUID, Attempt> attempts = new HashMap<>();

  LoginAttemptLimiter(int capacity, int maxAttempts, Duration resetAfter) {
    if (capacity < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("capacity and maxAttempts must be positive");
    }
    this.capacity = capacity;
    this.maxAttempts = maxAttempts;
    this.resetAfter = Objects.requireNonNull(resetAfter, "resetAfter");
    if (resetAfter.isZero() || resetAfter.isNegative()) {
      throw new IllegalArgumentException("resetAfter must be positive");
    }
  }

  synchronized boolean allow(UUID playerId, Instant now) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(now, "now");
    Attempt current = attempts.get(playerId);
    if (current != null && !now.isBefore(current.resetAt())) {
      attempts.remove(playerId);
      current = null;
    }
    if (current == null) {
      if (attempts.size() >= capacity) {
        attempts.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().resetAt()));
      }
      if (attempts.size() >= capacity) {
        return false;
      }
      attempts.put(playerId, new Attempt(1, now.plus(resetAfter)));
      return true;
    }
    Attempt next = new Attempt(current.count() + 1, current.resetAt());
    attempts.put(playerId, next);
    return next.count() <= maxAttempts;
  }

  synchronized void remove(UUID playerId) {
    attempts.remove(playerId);
  }

  synchronized void clear() {
    attempts.clear();
  }

  synchronized int size() {
    return attempts.size();
  }

  private record Attempt(int count, Instant resetAt) {}
}
