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

package io.github.addxiaoyi.starx.common.model;

import java.time.Instant;
import java.util.UUID;

/**
 * IP 会话记录 - 用于免密登录
 *
 * @param uuid 玩家 UUID
 * @param ipAddress 上次登录 IP 地址
 * @param isp ISP 运营商信息
 * @param location 地理位置
 * @param loginTime 登录时间（毫秒时间戳）
 * @param source 登录来源：local(本地密码)、premium(正版)、floodgate(基岩版)、skin(皮肤站)
 */
public record IpSession(
    UUID uuid,
    String ipAddress,
    String isp,
    String location,
    long loginTime,
    String source,
    String deviceFingerprint
) {

  public IpSession(
      UUID uuid, String ipAddress, String isp, String location, long loginTime, String source) {
    this(uuid, ipAddress, isp, location, loginTime, source, null);
  }

  /**
   * 创建新的 IP 会话
   */
  public static IpSession create(UUID uuid, String ipAddress, String source) {
    return new IpSession(uuid, ipAddress, null, null, System.currentTimeMillis(), source, null);
  }

  public static IpSession create(
      UUID uuid, String ipAddress, String source, String deviceFingerprint) {
    return new IpSession(
        uuid, ipAddress, null, null, System.currentTimeMillis(), source, deviceFingerprint);
  }

  /**
   * 创建带位置的 IP 会话
   */
  public static IpSession create(UUID uuid, String ipAddress, String isp, String location, String source) {
    return new IpSession(uuid, ipAddress, isp, location, System.currentTimeMillis(), source, null);
  }

  /**
   * 检查是否在指定的小时内
   */
  public boolean isWithinHours(int hours) {
    long now = System.currentTimeMillis();
    long expiryTime = loginTime + (hours * 60L * 60L * 1000L);
    return now < expiryTime;
  }

  /**
   * 获取登录时间（Instant）
   */
  public Instant loginInstant() {
    return Instant.ofEpochMilli(loginTime);
  }

  /**
   * 判断是否为正版来源
   */
  public boolean isPremiumSource() {
    return "premium".equalsIgnoreCase(source);
  }

  /**
   * 判断是否为基岩版来源
   */
  public boolean isFloodgateSource() {
    return "floodgate".equalsIgnoreCase(source);
  }

  /**
   * 判断是否为皮肤站来源
   */
  public boolean isSkinSiteSource() {
    return "skin".equalsIgnoreCase(source);
  }
}
