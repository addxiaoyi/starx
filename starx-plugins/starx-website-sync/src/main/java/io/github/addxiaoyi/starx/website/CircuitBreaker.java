package io.github.addxiaoyi.starx.website;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;
import java.util.function.Consumer;

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
  private boolean halfOpenProbeInFlight;
  private volatile Consumer<StateChange> stateChangeListener = ignored -> { };

  public record StateChange(State previous, State state) {
  }

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

  public void setStateChangeListener(Consumer<StateChange> listener) {
    this.stateChangeListener = Objects.requireNonNull(listener, "listener");
  }

  /**
   * 进入成功请求时调用。成功后熔断器重置到 CLOSED 状态。
   */
  public synchronized void recordSuccess() {
    this.failureCount.set(0);
    transitionTo(State.CLOSED);
    this.halfOpenProbeInFlight = false;
  }

  /**
   * 进入失败请求时调用。失败后计数器递增，计数达到阈值则打开熔断。
   */
  public synchronized void recordFailure() {
    int failures = this.failureCount.incrementAndGet();
    if (this.state == State.OPEN) {
      return; // 已经处于熔断状态，无需重复计数
    }
    if (failures >= this.failureThreshold) {
      transitionTo(State.OPEN);
      this.openStartTime.set(System.currentTimeMillis());
      this.halfOpenProbeInFlight = false;
    }
  }

  /**
   * 在发起请求前检查熔断器状态。
   * @return true 表示可以发起请求；false 表示熔断中，请求将被拒绝
   */
  public synchronized boolean allowRequest() {
    long now = System.currentTimeMillis();
    switch (this.state) {
      case CLOSED:
        return true;
      case OPEN:
        // 检查是否可以尝试半熔断
        if (Duration.ofMillis(now - this.openStartTime.get()).compareTo(this.timeout) >= 0) {
          transitionTo(State.HALF_OPEN);
          this.halfOpenProbeInFlight = true;
          return true;
        }
        return false;
      case HALF_OPEN:
        if (this.halfOpenProbeInFlight) return false;
        this.halfOpenProbeInFlight = true;
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
  public synchronized void reset() {
    transitionTo(State.CLOSED);
    this.failureCount.set(0);
    this.openStartTime.set(0);
    this.halfOpenProbeInFlight = false;
  }

  private void transitionTo(State next) {
    State previous = this.state;
    if (previous == next) {
      return;
    }
    this.state = next;
    try {
      this.stateChangeListener.accept(new StateChange(previous, next));
    } catch (RuntimeException error) {
      System.getLogger(CircuitBreaker.class.getName()).log(System.Logger.Level.WARNING,
          "Circuit breaker state listener failed: " + error.getClass().getSimpleName());
    }
  }
}
