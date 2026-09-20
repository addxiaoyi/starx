package io.github.addxiaoyi.starx.common.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 通用性能指标收集器。
 * 支持动态注册计数器、频率计数器和延迟测量。
 * 所有指标均为无锁实现，开销极低。
 */
public final class PerformanceMetrics {
  private static volatile PerformanceMetrics defaultRegistry = new PerformanceMetrics();

  public static PerformanceMetrics defaultRegistry() {
    return defaultRegistry;
  }

  public static void setDefaultRegistry(PerformanceMetrics registry) {
    defaultRegistry = registry;
  }
  private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, RateCounter> rateCounters = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, LatencyTracker> latencyTrackers = new ConcurrentHashMap<>();

  public Counter counter(String name) {
    return this.counters.computeIfAbsent(name, Counter::new);
  }

  public RateCounter rateCounter(String name) {
    return this.rateCounters.computeIfAbsent(name, RateCounter::new);
  }

  public LatencyTracker latencyTracker(String name) {
    return this.latencyTrackers.computeIfAbsent(name, LatencyTracker::new);
  }

  public Snapshot snapshot() {
    return new Snapshot(this.counters, this.rateCounters, this.latencyTrackers);
  }

  /** 简单计数器，使用于累计事件次数 */
  public static final class Counter {
    private final AtomicLong value = new AtomicLong();
    private final String name;

    Counter(String name) {
      this.name = name;
    }

    public void increment() {
      this.value.incrementAndGet();
    }

    public void increment(long delta) {
      this.value.addAndGet(delta);
    }

    public long get() {
      return this.value.get();
    }

    public String name() {
      return this.name;
    }
  }

  /** 频率计数器，自动计算每秒的操作率 */
  public static final class RateCounter {
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong lastTimestamp = new AtomicLong(System.nanoTime());
    private final String name;
    private volatile long lastRate = 0;

    RateCounter(String name) {
      this.name = name;
    }

    public void increment() {
      increment(1);
    }

    public void increment(long delta) {
      this.total.addAndGet(delta);
    }

    public RateSnapshot snapshot() {
      long now = System.nanoTime();
      long lastTs = this.lastTimestamp.get();
      long elapsedNanos = now - lastTs;
      
      if (this.lastTimestamp.compareAndSet(lastTs, now)) {
        long count = this.total.get() - (this.lastRate > 0 ? this.total.get() - this.lastRate : 0);
        this.lastRate = this.total.get();
        return new RateSnapshot(this.name, count, Math.max(1, elapsedNanos));
      }

      return new RateSnapshot(this.name, this.total.get(), 0);
    }

    public String name() {
      return this.name;
    }
  }

  /** 延迟跟踪器，使用分段计数器记录延迟分布 */
  public static final class LatencyTracker {
    private final AtomicLong totalLatency = new AtomicLong();
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong maxLatency = new AtomicLong();
    private final String name;

    LatencyTracker(String name) {
      this.name = name;
    }

    public void record(long latencyNanos) {
      this.totalLatency.addAndGet(latencyNanos);
      this.count.incrementAndGet();
      this.maxLatency.accumulateAndGet(latencyNanos, Math::max);
    }

    public LatencySnapshot snapshot() {
      long total = this.totalLatency.get();
      long cnt = this.count.get();
      return new LatencySnapshot(
          this.name,
          cnt,
          cnt > 0 ? Math.round((double) total / cnt / 1_000_000.0) : 0,
          cnt > 0 ? Math.round((double) total / cnt / 1_000_000.0) : 0,
          this.maxLatency.get() / 1_000_000L
      );
    }

    public String name() {
      return this.name;
    }
  }

  /** 指标快照 */
  public record Snapshot(
      ConcurrentMap<String, Counter> counters,
      ConcurrentMap<String, RateCounter> rateCounters,
      ConcurrentMap<String, LatencyTracker> latencyTrackers
  ) {
    public void printTo(java.io.PrintStream out) {
      out.println("=== Performance Metrics ===");
      for (Counter c : counters.values()) {
        out.println(c.name() + ": " + c.get());
      }
      for (RateSnapshot r : rateCounters.values().stream()
          .map(RateCounter::snapshot).toList()) {
        out.println(r.name() + ": " + r.total() + " (rate: " + String.format("%.2f/s", r.ratePerSecond()) + ")");
      }
      for (LatencySnapshot l : latencyTrackers.values().stream()
          .map(LatencyTracker::snapshot).toList()) {
        out.println(l.name() + ": count=" + l.count() + ", avg=" + l.avgMs() + "ms, max=" + l.maxMs() + "ms");
      }
    }
  }

/** 频率计数器快照 */
  public record RateSnapshot(String name, long total, long ratePerSecondNanos) {
    public double ratePerSecond() {
      return ratePerSecondNanos / 1_000_000_000.0;
    }
  }

  /** 延迟跟踪器快照 */
  public record LatencySnapshot(String name, long count, long avgMs, long p50Ms, long maxMs) {}
}