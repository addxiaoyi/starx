package io.github.addxiaoyi.starx.velocity.http;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class BoundedRateLimitRegistry {
  private final int capacity;
  private final int requestLimit;
  private final long windowMillis;
  private final Clock clock;
  private final ConcurrentHashMap<String, FixedWindowRateLimiter> limits = new ConcurrentHashMap<>();

  BoundedRateLimitRegistry(int capacity, int requestLimit, long windowMillis, Clock clock) {
    if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
    this.requestLimit = requestLimit;
    this.windowMillis = windowMillis;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  boolean tryAcquire(String identity) {
    String key = Objects.requireNonNull(identity, "identity");
    FixedWindowRateLimiter existing = limits.get(key);
    if (existing != null) return existing.tryAcquire();
    synchronized (limits) {
      existing = limits.get(key);
      if (existing != null) return existing.tryAcquire();
      prune();
      if (limits.size() >= capacity) return false;
      FixedWindowRateLimiter created =
          new FixedWindowRateLimiter(requestLimit, windowMillis, clock);
      limits.put(key, created);
      return created.tryAcquire();
    }
  }

  private void prune() {
    long cutoff = clock.millis() - windowMillis;
    limits.entrySet().removeIf(entry -> entry.getValue().expired(cutoff));
  }
}
