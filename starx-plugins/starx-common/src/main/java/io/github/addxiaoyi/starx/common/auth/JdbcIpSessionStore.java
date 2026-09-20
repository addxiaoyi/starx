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

import io.github.addxiaoyi.starx.common.database.JdbcIpSessionRepository;
import io.github.addxiaoyi.starx.common.model.IpSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 数据库实现的 IP 会话存储
 */
public class JdbcIpSessionStore implements IpSessionStore {

  private final JdbcIpSessionRepository repository;

  public JdbcIpSessionStore(JdbcIpSessionRepository repository) {
    this.repository = repository;
  }

  @Override
  public void save(IpSession session) {
    repository.save(session);
  }

  @Override
  public Optional<IpSession> findByUuidAndIp(UUID uuid, String ipAddress) {
    return repository.findByUuidAndIp(uuid, ipAddress);
  }

  @Override
  public boolean hasRecentSession(UUID uuid, String ipAddress, int hours) {
    return repository.hasRecentSession(uuid, ipAddress, hours);
  }

  @Override
  public List<IpSession> findRecentSessions(UUID uuid, int hours) {
    return repository.findRecentSessions(uuid, hours);
  }

  @Override
  public Optional<IpSession> findLatestByUuid(UUID uuid) {
    return repository.findLatestByUuid(uuid);
  }

  @Override
  public void deleteByUuid(UUID uuid) {
    repository.deleteByUuid(uuid);
  }
}
