package io.github.addxiaoyi.starx.velocity.metrics;

import java.util.concurrent.atomic.AtomicLong;

/** A bounded wall-clock minute counter for low-cost runtime diagnostics. */
public final class RuntimeWindowCounter {
  private final AtomicLong total = new AtomicLong();
  private final AtomicLong windowStart = new AtomicLong(currentMinute());
  private final AtomicLong windowValue = new AtomicLong();

  public void increment() {
    add(1);
  }

  public void add(long amount) {
    if (amount <= 0) return;
    rotate();
    windowValue.addAndGet(amount);
    total.addAndGet(amount);
  }

  public long perMinute() {
    rotate();
    return windowValue.get();
  }

  public long total() {
    return total.get();
  }

  private void rotate() {
    long minute = currentMinute();
    long previous = windowStart.get();
    if (previous == minute || !windowStart.compareAndSet(previous, minute)) return;
    windowValue.set(0);
  }

  private static long currentMinute() {
    return System.currentTimeMillis() / 60_000L;
  }
}
