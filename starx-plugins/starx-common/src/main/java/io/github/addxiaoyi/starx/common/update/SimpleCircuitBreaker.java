package io.github.addxiaoyi.starx.common.update;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简易熔断器，用于更新检查防止频繁重试。
 */
final class SimpleCircuitBreaker {
  private final int failureThreshold;
  private final Duration openTimeout;
  private final AtomicInteger failureCount = new AtomicInteger();
  private volatile long openSince = 0;

  SimpleCircuitBreaker(int failureThreshold, Duration openTimeout) {
    if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
    this.failureThreshold = failureThreshold;
    this.openTimeout = openTimeout;
  }

  boolean allowRequest() {
    long now = System.currentTimeMillis();
    if (this.openSince > 0 && now - this.openSince >= this.openTimeout.toMillis()) {
      // 半熔断恢复
      this.openSince = 0;
      this.failureCount.set(0);
      return true;
    }
    return this.failureCount.get() < this.failureThreshold;
  }

  void recordFailure() {
    int failures = this.failureCount.incrementAndGet();
    if (failures >= this.failureThreshold && this.openSince == 0) {
      this.openSince = System.currentTimeMillis();
    }
  }

  void recordSuccess() {
    this.failureCount.set(0);
    this.openSince = 0;
  }
}