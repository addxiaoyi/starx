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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;

/**
 * 内存实现的 IP 会话存储 - 用于不需要持久化的场景
 *
 * <p>该实现将 IP 会话存储在内存中，适合测试或单节点部署。
 * 如果需要持久化，可以使用 JdbcIpSessionStore。
 */
public class InMemoryIpSessionStore implements IpSessionStore {

  private static final int DEFAULT_MAX_SESSIONS = 4096;
  private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

  private final Map<UUID, Map<String, IpSession>> sessions = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration retention;
  private final int maxSessions;

  public InMemoryIpSessionStore() {
    this(Clock.systemUTC(), DEFAULT_RETENTION, DEFAULT_MAX_SESSIONS);
  }

  InMemoryIpSessionStore(Clock clock, Duration retention, int maxSessions) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.retention = Objects.requireNonNull(retention, "retention");
    if (retention.isZero() || retention.isNegative()) {
      throw new IllegalArgumentException("retention must be positive");
    }
    if (maxSessions <= 0) throw new IllegalArgumentException("maxSessions must be positive");
    this.maxSessions = maxSessions;
  }

  @Override
  public void save(IpSession session) {
    Objects.requireNonNull(session, "session");
    cleanupBefore(this.clock.millis() - this.retention.toMillis());
    sessions.computeIfAbsent(session.uuid(), k -> new ConcurrentHashMap<>())
        .put(session.ipAddress(), session);
    trim();
  }

  @Override
  public Optional<IpSession> findByUuidAndIp(UUID uuid, String ipAddress) {
    Map<String, IpSession> playerSessions = sessions.get(uuid);
    if (playerSessions == null) {
      return Optional.empty();
    }
    IpSession session = playerSessions.get(ipAddress);
    if (session == null) {
      return Optional.empty();
    }
    long cutoff = this.clock.millis() - this.retention.toMillis();
    if (session.loginTime() >= cutoff) {
      return Optional.of(session);
    }
    playerSessions.remove(ipAddress, session);
    if (playerSessions.isEmpty()) {
      sessions.remove(uuid, playerSessions);
    }
    return Optional.empty();
  }

  @Override
  public boolean hasRecentSession(UUID uuid, String ipAddress, int hours) {
    Optional<IpSession> session = findByUuidAndIp(uuid, ipAddress);
    return hours > 0 && session.isPresent()
        && this.clock.millis() - session.get().loginTime() < hours * 3_600_000L;
  }

  @Override
  public boolean hasRecentSessionMinutes(UUID uuid, String ipAddress, int minutes) {
    return false;
  }

  @Override
  public boolean hasRecentSessionMinutes(
      UUID uuid, String ipAddress, String deviceFingerprint, int minutes) {
    if (deviceFingerprint == null || deviceFingerprint.isBlank()) return false;
    Optional<IpSession> session = findByUuidAndIp(uuid, ipAddress);
    return minutes > 0 && session.isPresent()
        && deviceFingerprint.equals(session.get().deviceFingerprint())
        && "local".equalsIgnoreCase(session.get().source())
        && this.clock.millis() - session.get().loginTime() >= 0
        && this.clock.millis() - session.get().loginTime() < minutes * 60_000L;
  }

  @Override
  public List<IpSession> findRecentSessions(UUID uuid, int hours) {
    long cutoff = this.clock.millis() - (hours * 60L * 60L * 1000L);
    Map<String, IpSession> playerSessions = sessions.get(uuid);
    if (playerSessions == null) {
      return List.of();
    }
    return playerSessions.values().stream()
        .filter(s -> s.loginTime() > cutoff)
        .sorted((a, b) -> Long.compare(b.loginTime(), a.loginTime()))
        .toList();
  }

  @Override
  public Optional<IpSession> findLatestByUuid(UUID uuid) {
    Map<String, IpSession> playerSessions = sessions.get(uuid);
    if (playerSessions == null || playerSessions.isEmpty()) {
      return Optional.empty();
    }
    return playerSessions.values().stream()
        .max((a, b) -> Long.compare(a.loginTime(), b.loginTime()));
  }

  @Override
  public void deleteByUuid(UUID uuid) {
    sessions.remove(uuid);
  }

  /**
   * 清理过期记录
   */
  public void cleanupExpired(int hours) {
    cleanupBefore(this.clock.millis() - (hours * 60L * 60L * 1000L));
  }

  /**
   * 获取当前会话数量
   */
  public int size() {
    return sessions.values().stream().mapToInt(Map::size).sum();
  }

  private void cleanupBefore(long cutoff) {
    sessions.entrySet().removeIf(entry -> {
      entry.getValue().entrySet().removeIf(item -> item.getValue().loginTime() < cutoff);
      return entry.getValue().isEmpty();
    });
  }

  private void trim() {
    while (size() > this.maxSessions) {
      sessions.entrySet().stream()
          .flatMap(player -> player.getValue().entrySet().stream()
              .map(entry -> new StoredSession(player.getKey(), entry.getKey(), entry.getValue())))
          .min(Comparator.comparingLong(entry -> entry.session().loginTime()))
          .ifPresent(oldest -> {
            Map<String, IpSession> playerSessions = sessions.get(oldest.playerId());
            if (playerSessions == null) return;
            playerSessions.remove(oldest.ipAddress(), oldest.session());
            if (playerSessions.isEmpty()) sessions.remove(oldest.playerId(), playerSessions);
          });
    }
  }

  private record StoredSession(UUID playerId, String ipAddress, IpSession session) { }
}
