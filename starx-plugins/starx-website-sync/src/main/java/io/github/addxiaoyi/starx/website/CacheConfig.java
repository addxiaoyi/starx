package io.github.addxiaoyi.starx.website;

/**
 * 纹理缓存配置
 */
public record CacheConfig(
    // LRU缓存最大条目数
    int maxEntries,
    // 缓存过期时间（秒）
    int ttlSeconds,
    // 缓存清理间隔（秒）
    int cleanupIntervalSeconds
) {
    public CacheConfig {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must be non-negative");
        }
        if (cleanupIntervalSeconds < 1) {
            throw new IllegalArgumentException("cleanupIntervalSeconds must be at least 1");
        }
    }

    public static CacheConfig defaults() {
        return new CacheConfig(10000, 3600, 300);
    }
}
