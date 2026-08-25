/*
 * Copyright (c) 2024-2026 StarMC Team and contributors.
 * Use of this source code is governed by the MIT License.
 */
package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.api.repository.UserRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 专门处理正版玩家认证和兼容性的增强处理器。
 * 提供正版玩家身份验证缓存、快速通行判断和认证服务器同步。
 */
public final class PremiumPlayerHandler {

    private static final Logger LOGGER = Logger.getLogger(PremiumPlayerHandler.class.getName());

    // 正版玩家 UUID 验证缓存
    private static final ConcurrentMap<UUID, PremiumVerification> verificationCache = new ConcurrentHashMap<>();
    // 正版玩家快速通行缓存（有效期 5 分钟）
    private static final ConcurrentMap<String, Instant> recentPremiumAuth = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 300000L; // 5 分钟
    private static final long VERIFICATION_TTL_MS = 600000L; // 10 分钟

    private final PremiumResolver premiumResolver;
    private final UserRepository userRepository;
    private final YggdrasilAuthenticator yggdrasilAuth;

    public PremiumPlayerHandler(PremiumResolver premiumResolver, UserRepository userRepository) {
        this(premiumResolver, userRepository, null);
    }

    public PremiumPlayerHandler(PremiumResolver premiumResolver, UserRepository userRepository, YggdrasilAuthenticator yggdrasilAuth) {
        this.premiumResolver = premiumResolver;
        this.userRepository = userRepository;
        this.yggdrasilAuth = yggdrasilAuth;
    }

    /**
     * 检查玩家是否为正版玩家（带缓存）
     */
    public boolean isPremium(UUID uuid, boolean onlineMode) {
        // 首先检查缓存
        PremiumVerification cached = verificationCache.get(uuid);
        if (cached != null && !cached.isExpired(VERIFICATION_TTL_MS)) {
            return cached.isPremium();
        }

        // 直接检查在线模式
        if (!onlineMode) {
            return false;
        }

        // 通过 PremiumResolver 检查
        boolean isPremium = premiumResolver.isPremium(uuid, onlineMode);

        // 缓存验证结果
        verificationCache.put(uuid, new PremiumVerification(isPremium));

        return isPremium;
    }

    /**
     * 异步验证正版玩家身份
     */
    public void verifyPremiumAsync(UUID uuid, String username, String serverId, String ip,
                                   String serverName, java.util.function.Consumer<Boolean> callback) {
        if (yggdrasilAuth == null) {
            callback.accept(premiumResolver.isPremium(uuid, true));
            return;
        }

        yggdrasilAuth.authenticate(username, serverId, ip, serverName)
            .thenAccept(result -> {
                if (result != null) {
                    verificationCache.put(uuid, new PremiumVerification(true));
                    markRecentAuth(uuid.toString() + ":" + result);
                }
                callback.accept(result != null);
            })
            .exceptionally(ex -> {
                LOGGER.log(Level.FINE, "Premium verification failed for " + uuid + ": " + ex.getMessage());
                callback.accept(false);
                return null;
            });
    }

    /**
     * 标记最近的正版认证成功
     */
    public void markRecentAuth(String key) {
        recentPremiumAuth.put(key, Instant.now().plusMillis(CACHE_TTL_MS));
    }

    /**
     * 检查是否为最近认证的正版玩家
     */
    public boolean isRecentAuth(String key) {
        Instant expiry = recentPremiumAuth.get(key);
        if (expiry == null) {
            return false;
        }
        if (Instant.now().isAfter(expiry)) {
            recentPremiumAuth.remove(key);
            return false;
        }
        return true;
    }

    /**
     * 清理过期缓存
     */
    public void cleanupExpiredCache() {
        Instant now = Instant.now();
        
        // 清理过期验证记录
        verificationCache.entrySet().removeIf(entry -> entry.getValue().isExpired(VERIFICATION_TTL_MS));
        
        // 清理最近认证记录
        recentPremiumAuth.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
        
        // 清理过时的认证缓存
        if (!recentPremiumAuth.isEmpty()) {
            LOGGER.fine("PremiumPlayerHandler cache cleanup: " + verificationCache.size() + " verifications, "
                + recentPremiumAuth.size() + " recent auth records remaining.");
        }
    }

    /**
     * 更新正版玩家状态到数据库
     */
    public void syncPremiumStatus(UUID uuid, boolean isPremium) {
        try {
            Optional<UserDto> userOpt = userRepository.findByUuid(uuid);
            if (userOpt.isPresent()) {
                UserDto user = userOpt.get();
                if (user.premium() != isPremium) {
                    if (userRepository instanceof JdbcUserRepository) {
                        ((JdbcUserRepository) userRepository).updatePremium(uuid, isPremium);
                    }
                    LOGGER.fine("Updated premium status for " + uuid + ": " + isPremium);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to sync premium status for " + uuid, e);
        }
    }

    /**
     * 使指定玩家的正版验证缓存失效
     */
    public void invalidate(UUID uuid) {
        verificationCache.remove(uuid);
        premiumResolver.invalidate(uuid);
    }

    /**
     * 清除所有缓存
     */
    public void clearAll() {
        verificationCache.clear();
        recentPremiumAuth.clear();
        premiumResolver.clearCache();
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getCacheStats() {
        cleanupExpiredCache();
        return new CacheStats(verificationCache.size(), recentPremiumAuth.size());
    }

    /**
     * 缓存统计信息
     */
    public record CacheStats(int verificationCacheSize, int recentAuthCacheSize) {}

    /**
     * 正版验证缓存记录
     */
    private static final class PremiumVerification {
        private final boolean isPremium;
        private final long timestamp;

        PremiumVerification(boolean isPremium) {
            this.isPremium = isPremium;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isPremium() {
            return isPremium;
        }

        public boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }
    }
}
