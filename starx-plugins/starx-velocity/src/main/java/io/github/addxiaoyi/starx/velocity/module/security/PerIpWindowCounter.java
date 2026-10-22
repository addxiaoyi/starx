package io.github.addxiaoyi.starx.velocity.module.security;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class PerIpWindowCounter {
  private final int capacity;
  private final long windowMillis;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private static final int EVICTION_SAMPLE_SIZE = 32;
  private final Object trimLock = new Object();
  private Iterator<Map.Entry<String, Window>> evictionCursor;

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
    synchronized (this.trimLock) {
      this.windows.entrySet().removeIf(entry -> entry.getValue().lastSeenAt < cutoff);
      if (this.windows.isEmpty()) {
        this.evictionCursor = null;
      }
    }
  }

  int size() { return this.windows.size(); }
  void clear() {
    synchronized (this.trimLock) {
      this.windows.clear();
      this.evictionCursor = null;
    }
  }

  private void trim() {
    if (this.windows.size() <= this.capacity) return;
    synchronized (this.trimLock) {
      while (this.windows.size() > this.capacity) {
        if (this.evictionCursor == null || !this.evictionCursor.hasNext()) {
          this.evictionCursor = this.windows.entrySet().iterator();
        }
        Map.Entry<String, Window> oldest = null;
        int inspected = 0;
        while (inspected < EVICTION_SAMPLE_SIZE && this.evictionCursor.hasNext()) {
          Map.Entry<String, Window> candidate = this.evictionCursor.next();
          inspected++;
          if (oldest == null
              || candidate.getValue().lastSeenAt() < oldest.getValue().lastSeenAt()) {
            oldest = candidate;
          }
        }
        if (oldest == null) {
          this.evictionCursor = null;
          return;
        }
        this.windows.remove(oldest.getKey(), oldest.getValue());
      }
    }
  }
  private record Window(long startedAt, long lastSeenAt, int count) { }
}
