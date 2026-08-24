package io.github.addxiaoyi.starx.website;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 线程安全的 LRU + TTL 缓存。
 * 替换原先"满则整体清空"的策略，避免缓存雪崩导致的重复下载风暴。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public final class LruTtlCache<K, V> {
  private final int maxEntries;
  private final long ttlMillis;
  private final ReentrantLock lock = new ReentrantLock();
  // accessOrder=true 使 LinkedHashMap 按访问顺序排序，实现 LRU 淘汰
  private LinkedHashMap<K, Entry<V>> map;

  private static final class Entry<V> {
    final V value;
    final long expiresAtMillis;

    Entry(V value, long expiresAtMillis) {
      this.value = value;
      this.expiresAtMillis = expiresAtMillis;
    }

    boolean expired(long nowMillis) {
      return nowMillis >= this.expiresAtMillis;
    }
  }

  public LruTtlCache(int maxEntries, long ttlMillis) {
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries must be at least 1");
    }
    if (ttlMillis < 0) {
      throw new IllegalArgumentException("ttlMillis must be non-negative");
    }
    this.maxEntries = maxEntries;
    this.ttlMillis = ttlMillis;
    this.map = newLinkedHashMap();
  }

  /**
   * 获取缓存的值；未命中或已过期返回 null，并移除过期条目。
   */
  public V get(K key, long nowMillis) {
    this.lock.lock();
    try {
      Entry<V> entry = this.map.get(key);
      if (entry == null) {
        return null;
      }
      if (entry.expired(nowMillis)) {
        this.map.remove(key);
        return null;
      }
      return entry.value;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * 放入值；若键已存在则覆盖并刷新 TTL 与 LRU 顺序。超容量时淘汰最久未使用的条目。
   */
  public void put(K key, V value, long nowMillis) {
    Objects.requireNonNull(value, "value");
    this.lock.lock();
    try {
      this.map.put(key, new Entry<>(value, nowMillis + this.ttlMillis));
      evictIfNeeded(nowMillis);
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * 获取或加载：命中直接返回，否则用 loader 计算后放入缓存。
   */
  public V computeIfAbsent(K key, long nowMillis, Function<K, V> loader) {
    this.lock.lock();
    try {
      Entry<V> entry = this.map.get(key);
      if (entry != null && !entry.expired(nowMillis)) {
        return entry.value;
      }
      if (entry != null) {
        this.map.remove(key);
      }
    } finally {
      this.lock.unlock();
    }
    V loaded = loader.apply(key);
    if (loaded != null) {
      put(key, loaded, nowMillis);
    }
    return loaded;
  }

  /**
   * 移除过期条目。由外部按 cleanupInterval 定期调用，也可在容量压力时自动触发。
   */
  public int removeExpired(long nowMillis) {
    this.lock.lock();
    try {
      int before = this.map.size();
      this.map.entrySet().removeIf(e -> e.getValue().expired(nowMillis));
      return before - this.map.size();
    } finally {
      this.lock.unlock();
    }
  }

  public void clear() {
    this.lock.lock();
    try {
      this.map.clear();
    } finally {
      this.lock.unlock();
    }
  }

  public int size() {
    this.lock.lock();
    try {
      return this.map.size();
    } finally {
      this.lock.unlock();
    }
  }

  private void evictIfNeeded(long nowMillis) {
    if (this.map.size() <= this.maxEntries) {
      return;
    }
    // 先清一轮过期条目，减少不必要的 LRU 淘汰
    removeExpiredUnsafe(nowMillis);
    while (this.map.size() > this.maxEntries) {
      Map.Entry<K, Entry<V>> eldest = this.map.entrySet().iterator().next();
      this.map.remove(eldest.getKey());
    }
  }

  private void removeExpiredUnsafe(long nowMillis) {
    this.map.entrySet().removeIf(e -> e.getValue().expired(nowMillis));
  }

  private LinkedHashMap<K, Entry<V>> newLinkedHashMap() {
    return new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
        return false; // 由 evictIfNeeded 显式控制淘汰时机
      }
    };
  }
}
