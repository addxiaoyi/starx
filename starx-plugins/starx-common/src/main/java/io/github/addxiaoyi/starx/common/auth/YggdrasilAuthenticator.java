/*
 * Copyright (c) 2024-2026 StarMC Team and contributors.
 * Use of this source code is governed by the MIT License.
 */
package io.github.addxiaoyi.starx.common.auth;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 正版玩家认证服务接口
 * 提供与 Mojang/Yggdrasil 认证服务的集成
 */
public interface YggdrasilAuthenticator {

    /**
     * 认证玩家到 Yggdrasil 服务
     * 
     * @param username 玩家用户名
     * @param serverId 服务器 ID
     * @param ip 玩家 IP 地址
     * @param serverName 服务器名称
     * @return 返回 accessToken 如果认证成功，否则返回 null
     */
    CompletableFuture<String> authenticate(String username, String serverId, String ip, String serverName);

    /**
     * 验证玩家的 accessToken
     * 
     * @param username 玩家用户名
     * @param accessToken accessToken
     * @return 是否有效
     */
    CompletableFuture<Boolean> validateAccessToken(String username, String accessToken);

    /**
     * 获取玩家的 UUID（从 Mojang API）
     * 
     * @param username 玩家用户名
     * @return 玩家的 UUID
     */
    CompletableFuture<UUID> getUuidByUsername(String username);

    /**
     * 获取玩家的用户名（从 Mojang API）
     * 
     * @param uuid 玩家 UUID
     * @return 玩家的用户名
     */
    CompletableFuture<String> getUsernameByUuid(UUID uuid);

    /**
     * 检查玩家是否拥有有效的 Mojang 账户
     * 
     * @param uuid 玩家 UUID
     * @return 是否为正版玩家
     */
    CompletableFuture<Boolean> isValidPremiumPlayer(UUID uuid);

    /**
     * 刷新 accessToken
     * 
     * @param accessToken 当前 accessToken
     * @param clientToken 客户端 token
     * @return 新的 accessToken
     */
    CompletableFuture<String> refreshAccessToken(String accessToken, String clientToken);

    /**
     * 使 accessToken 失效
     * 
     * @param accessToken accessToken
     */
    CompletableFuture<Void> invalidateAccessToken(String accessToken);
}
