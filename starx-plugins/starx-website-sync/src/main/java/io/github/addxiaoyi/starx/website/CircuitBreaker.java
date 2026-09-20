package io.github.addxiaoyi.starx.website;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单的熔断器实现。
 * 防止持续请求失效时对后端造成雪崩式压力。
 *
 * 状态转移:
 * CLOSED(正常) --失败次数>=阈值--> OPEN(熔断) --超时后--> HALF_OPEN(半熔断)
 *   ↑                                                              |
 *   └── 成功请求恢复 ──────────────────────────────────────────────┘
 */
public final class CircuitBreaker {
  public enum State {
    CLOSED,   // 正常模式
    OPEN,     // 熔断模式
    HALF_OPEN // 半熔断模式，测试后恢复 FULL
  }

  private final int failureThreshold;
  private final Duration timeout;
  private final Duration halfOpenWait;

  private volatile State state = State.CLOSED;
  private final AtomicInteger failureCount = new AtomicInteger(0);
  private final AtomicLong openStartTime = new AtomicLong(0);

  public CircuitBreaker(int failureThreshold, Duration timeout) {
    this(failureThreshold, timeout, Duration.ofSeconds(30), true);
  }

  public CircuitBreaker(int failureThreshold, Duration timeout, Duration halfOpenWait, boolean enabled) {
    if (failureThreshold < 1) {
      throw new IllegalArgumentException("failureThreshold must be at least 1");
    }
    if (timeout.toMillis() < 0) {
      throw new IllegalArgumentException("timeout must be non-negative");
    }
    this.failureThreshold = failureThreshold;
    this.timeout = timeout;
    this.halfOpenWait = halfOpenWait;
    // 如果启用，保持 enable=true；否则始终 CLOSED
  }

  /**
   * 进入成功请求时调用。成功后熔断器重置到 CLOSED 状态。
   */
  public void recordSuccess() {
    this.failureCount.set(0);
    this.state = State.CLOSED;
  }

  /**
   * 进入失败请求时调用。失败后计数器递增，计数达到阈值则打开熔断。
   */
  public void recordFailure() {
    int failures = this.failureCount.incrementAndGet();
    if (this.state == State.OPEN) {
      return; // 已经处于熔断状态，无需重复计数
    }
    if (failures >= this.failureThreshold) {
      this.state = State.OPEN;
      this.openStartTime.set(System.currentTimeMillis());
    }
  }

  /**
   * 在发起请求前检查熔断器状态。
   * @return true 表示可以发起请求；false 表示熔断中，请求将被拒绝
   */
  public boolean allowRequest() {
    long now = System.currentTimeMillis();
    switch (this.state) {
      case CLOSED:
        return true;
      case OPEN:
        // 检查是否可以尝试半熔断
        if (Duration.ofMillis(now - this.openStartTime.get()).compareTo(this.timeout) >= 0) {
          this.state = State.HALF_OPEN;
          return true;
        }
        return false;
      case HALF_OPEN:
        return true;
      default:
        return true;
    }
  }

  /**
   * 获取当前熔断器状态。
   */
  public State getState() {
    return this.state;
  }

  /**
   * 获取当前失败计数。
   */
  public int getFailureCount() {
    return this.failureCount.get();
  }

  /**
   * 重置熔断器到初始状态。
   */
  public void reset() {
    this.state = State.CLOSED;
    this.failureCount.set(0);
    this.openStartTime.set(0);
  }
}