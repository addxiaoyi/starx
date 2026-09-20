package io.github.addxiaoyi.starx.velocity.http;

import java.time.Clock;
import java.util.Objects;

final class FixedWindowRateLimiter {
  private final int limit;
  private final long windowMillis;
  private final Clock clock;
  private long windowStart;
  private int count;

  FixedWindowRateLimiter(int limit, long windowMillis, Clock clock) {
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    if (windowMillis <= 0) throw new IllegalArgumentException("windowMillis must be positive");
    this.limit = limit;
    this.windowMillis = windowMillis;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.windowStart = clock.millis();
  }

  synchronized boolean tryAcquire() {
    long now = this.clock.millis();
    if (now - this.windowStart > this.windowMillis) {
      this.windowStart = now;
      this.count = 0;
    }
    this.count++;
    return this.count <= this.limit;
  }

  synchronized boolean expired(long cutoff) {
    return this.windowStart < cutoff;
  }
}
