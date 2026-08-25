/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.smart;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.addxiaoyi.starx.common.metrics.PerformanceMetrics;

public final class SmartCache<K, V> {
    private static final Logger logger = Logger.getLogger(SmartCache.class.getName());
    private static final ScheduledExecutorService preloader = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "starx-smart-cache-preloader");
        t.setDaemon(true);
        return t;
    });
    private final int maxSize;
    private final long ttlMillis;
    private final ConcurrentHashMap<K, CacheEntry> cache;
    private final Function<K, V> loader;
    private final ConcurrentHashMap<K, Integer> accessCounts;
    private final PerformanceMetrics.LatencyTracker loadTracker;
    private final PerformanceMetrics.RateCounter hitCounter;
    private final PerformanceMetrics.RateCounter missCounter;

    public SmartCache(int maxSize, long ttlMillis, Function<K, V> loader) {
        this(maxSize, ttlMillis, loader, "SmartCache");
    }

    public SmartCache(int maxSize, long ttlMillis, Function<K, V> loader, String metricsName) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
        this.cache = new ConcurrentHashMap(Math.min(maxSize, 64));
        this.loader = loader;
        this.accessCounts = new ConcurrentHashMap();
        this.loadTracker = PerformanceMetrics.defaultRegistry().latencyTracker(metricsName + ".loadLatency");
        this.hitCounter = PerformanceMetrics.defaultRegistry().rateCounter(metricsName + ".hits");
        this.missCounter = PerformanceMetrics.defaultRegistry().rateCounter(metricsName + ".misses");
        // 每 30 秒定时清理过期条目，避免过期条目长期占用内存
        long cleanupInterval = Math.max(ttlMillis / 2, 30_000L);
        preloader.scheduleAtFixedRate(this::evictExpired, cleanupInterval, cleanupInterval, TimeUnit.MILLISECONDS);
    }

    public static <K, V> SmartCache<K, V> withDefaults(Function<K, V> loader) {
        return new SmartCache<K, V>(500, 60000L, loader);
    }

    public V get(K key) {
        CacheEntry entry = this.cache.get(key);
        if (entry != null && !entry.isExpired()) {
            this.accessCounts.merge(key, 1, Integer::sum);
            this.hitCounter.increment();
            return entry.value;
        }
        this.missCounter.increment();
        return this.loadAndCache(key);
    }

    public V getIfPresent(K key) {
        CacheEntry entry = this.cache.get(key);
        if (entry != null && !entry.isExpired()) {
            this.accessCounts.merge(key, 1, Integer::sum);
            this.hitCounter.increment();
            return entry.value;
        }
        this.missCounter.increment();
        return null;
    }

    public void put(K key, V value) {
        int accessCount = this.accessCounts.getOrDefault(key, 0);
        long extendedTtl = this.ttlMillis + Math.min(accessCount * 1000L, this.ttlMillis);
        this.cache.put(key, new CacheEntry(value, System.currentTimeMillis() + extendedTtl, accessCount));
        this.evictIfNeeded();
    }

    public void preload(K key) {
        preloader.execute(() -> {
            try {
                V value;
                if (this.getIfPresent(key) == null && (value = this.loader.apply(key)) != null) {
                    this.put(key, value);
                }
            }
            catch (Exception e) {
                logger.log(Level.FINE, "SmartCache preload failed for key: {0}", key);
            }
        });
    }

    public Map<K, Integer> topAccessKeys(int n) {
        return this.accessCounts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(n)
            .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), HashMap::putAll);
    }

    public int size() {
        return this.cache.size();
    }

    public void clear() {
        this.cache.clear();
        this.accessCounts.clear();
    }

    /**
     * 定时清理所有过期条目
     */
    private void evictExpired() {
        try {
            long now = System.currentTimeMillis();
            this.cache.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
            logger.log(Level.FINE, "SmartCache evicted expired entries, current size: {0}", this.cache.size());
        } catch (Exception e) {
            logger.log(Level.WARNING, "SmartCache evictExpired failed", e);
        }
    }

    private V loadAndCache(K key) {
        V value = this.loader.apply(key);
        if (value != null) {
            this.put(key, value);
            if (this.accessCounts.getOrDefault(key, 0) > 5) {
                this.preloadRelated(key);
            }
        }
        return value;
    }

    protected void preloadRelated(K key) {
    }

    private void evictIfNeeded() {
        while (this.cache.size() > this.maxSize) {
            this.cache.entrySet().stream().min((a, b) -> Long.compare(((CacheEntry)a.getValue()).expiresAt, ((CacheEntry)b.getValue()).expiresAt)).ifPresent(e -> this.cache.remove(e.getKey()));
        }
    }

    private final class CacheEntry {
        final V value;
        final long expiresAt;
        final int accessCount;

        CacheEntry(SmartCache smartCache, V value, long expiresAt) {
            this(value, expiresAt, 0);
        }

        CacheEntry(V value, long expiresAt, int accessCount) {
            this.value = value;
            this.expiresAt = expiresAt;
            this.accessCount = accessCount;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > this.expiresAt;
        }
    }
}
