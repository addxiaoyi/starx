package io.github.addxiaoyi.starx.velocity.module.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

final class LoginAttemptLimiter {
  private final int capacity;
  private final int maxAttempts;
  private final Duration resetAfter;
  private final ConcurrentMap<UUID, Attempt> attempts = new ConcurrentHashMap<>();

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

  boolean allow(UUID playerId, Instant now) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(now, "now");
    
    // 先尝试移除过期条目
    Attempt current = attempts.get(playerId);
    if (current != null && !now.isBefore(current.resetAt())) {
      attempts.remove(playerId);
      current = null;
    }
    
    if (current == null) {
      // 容量检查和清理
      if (attempts.size() >= capacity) {
        cleanExpired(now);
      }
      if (attempts.size() >= capacity) {
        return false;
      }
      Attempt newAttempt = new Attempt(1, now.plus(resetAfter));
      Attempt existing = attempts.putIfAbsent(playerId, newAttempt);
      return existing == null;
    }
    
    // 更新尝试次数
    int newCount = current.count() + 1;
    Attempt next = new Attempt(newCount, current.resetAt());
    attempts.put(playerId, next);
    return newCount <= maxAttempts;
  }

  void remove(UUID playerId) {
    attempts.remove(playerId);
  }

  void clear() {
    attempts.clear();
  }

  int size() {
    return attempts.size();
  }

  private void cleanExpired(Instant now) {
    Predicate<Attempt> isExpired = attempt -> !now.isBefore(attempt.resetAt());
    attempts.entrySet().removeIf(entry -> isExpired.test(entry.getValue()));
  }

  private record Attempt(int count, Instant resetAt) {}
}
