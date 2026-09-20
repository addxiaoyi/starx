package io.github.addxiaoyi.starx.velocity.module.security;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class PerIpWindowCounter {
  private final int capacity;
  private final long windowMillis;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final Object trimLock = new Object();

  PerIpWindowCounter(int capacity, long windowMillis) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
    if (windowMillis <= 0) throw new IllegalArgumentException("windowMillis must be positive");
    this.capacity = capacity;
    this.windowMillis = windowMillis;
  }

  int increment(String key, long now) {
    String normalized = Objects.requireNonNull(key, "key");
    Window current = this.windows.compute(normalized, (ignored, previous) -> {
      boolean expired = previous == null || now < previous.startedAt
          || now - previous.startedAt >= this.windowMillis;
      return expired
          ? new Window(now, now, 1)
          : new Window(previous.startedAt, now, previous.count + 1);
    });
    this.trim();
    return current.count;
  }

  int count(String key, long now) {
    Window current = this.windows.get(key);
    if (current == null) return 0;
    boolean expired = now < current.startedAt || now - current.startedAt >= this.windowMillis;
    if (!expired) return current.count;
    this.windows.remove(key, current);
    return 0;
  }

  void purgeBefore(long cutoff) {
    this.windows.entrySet().removeIf(entry -> entry.getValue().lastSeenAt < cutoff);
  }

  int size() { return this.windows.size(); }
  void clear() { this.windows.clear(); }

  private void trim() {
    if (this.windows.size() <= this.capacity) return;
    synchronized (this.trimLock) {
      while (this.windows.size() > this.capacity) {
        this.windows.entrySet().stream()
            .min(Comparator.comparingLong(entry -> entry.getValue().lastSeenAt))
            .ifPresent(oldest -> this.windows.remove(oldest.getKey(), oldest.getValue()));
      }
    }
  }

  private record Window(long startedAt, long lastSeenAt, int count) { }
}
