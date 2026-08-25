/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.common.smart.SmartCache;

import java.util.UUID;

public final class PremiumResolver {
    private static final long CACHE_TTL_MS = 300000L;
    private static final int CACHE_MAX_SIZE = 10000;
    
    private final SmartCache<UUID, Boolean> premiumCache;

    public PremiumResolver() {
        this.premiumCache = new SmartCache<>(CACHE_MAX_SIZE, CACHE_TTL_MS, k -> null);
    }

    public boolean isPremium(UUID uuid, boolean onlineMode) {
        if (uuid == null) {
            return onlineMode;
        }
        
        Boolean cached = premiumCache.getIfPresent(uuid);
        if (cached != null) {
            return cached;
        }
        
        boolean result = onlineMode;
        premiumCache.put(uuid, result);
        return result;
    }
    
    public void invalidate(UUID uuid) {
        if (uuid != null) {
            premiumCache.remove(uuid);
        }
    }
    
    public void clearCache() {
        premiumCache.clear();
    }
    
    public int getCacheSize() {
        return premiumCache.size();
    }
}
