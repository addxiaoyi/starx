/*
 * Copyright (C) 2025 StarX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.common.model.IpSession;
import java.util.Optional;
import java.util.UUID;

/**
 * IP 会话存储接口 - 定义 IP 免密登录记录的存储操作
 *
 * <p>该接口解耦了认证服务和具体的数据库实现，允许使用内存存储或数据库存储。
 */
public interface IpSessionStore {

  /**
   * 保存 IP 会话记录
   */
  void save(IpSession session);

  /**
   * 查找指定玩家和 IP 的会话记录
   */
  Optional<IpSession> findByUuidAndIp(UUID uuid, String ipAddress);

  /**
   * 检查是否存在有效的 IP 免密记录
   *
   * @param uuid 玩家 UUID
   * @param ipAddress IP 地址
   * @param hours 有效期（小时）
   * @return 是否存在有效的免密记录
   */
  boolean hasRecentSession(UUID uuid, String ipAddress, int hours);

  /** Minute-precision variant used by the short-lived same-network password bypass. */
  default boolean hasRecentSessionMinutes(UUID uuid, String ipAddress, int minutes) {
    return false;
  }

  default boolean hasRecentSessionMinutes(
      UUID uuid, String ipAddress, String deviceFingerprint, int minutes) {
    if (deviceFingerprint == null || deviceFingerprint.isBlank()) return false;
    if (minutes <= 0) return false;
    return this.findByUuidAndIp(uuid, ipAddress)
        .filter(session -> deviceFingerprint.equals(session.deviceFingerprint()))
        .filter(session -> "local".equalsIgnoreCase(session.source()))
        .map(IpSession::loginTime)
        .map(loginTime -> System.currentTimeMillis() - loginTime < minutes * 60_000L)
        .orElse(false);
  }

  /**
   * 查找玩家最近的登录记录
   *
   * @param uuid 玩家 UUID
   * @param hours 查找范围（小时）
   * @return 最近的会话记录列表
   */
  java.util.List<IpSession> findRecentSessions(UUID uuid, int hours);

  /**
   * 获取玩家的最新登录会话
   */
  Optional<IpSession> findLatestByUuid(UUID uuid);

  /**
   * 删除玩家的所有 IP 会话记录
   */
  void deleteByUuid(UUID uuid);
}
