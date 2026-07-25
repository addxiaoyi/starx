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

package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.model.IpSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * IP 会话 Repository - 管理玩家的 IP 免密登录记录
 */
public class JdbcIpSessionRepository {

  private static final String SELECT_COLUMNS =
      "player_uuid, ip_address, isp, location, login_time, source, device_fingerprint";

  private final DataSource dataSource;

  public JdbcIpSessionRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * 保存或更新 IP 会话记录
   */
  public void save(IpSession session) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        if (updateSession(connection, session) == 0) {
          try {
            insertSession(connection, session);
          } catch (SQLException conflict) {
            if (updateSession(connection, session) == 0) throw conflict;
          }
        }
        connection.commit();
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      }
    } catch (SQLException error) {
      throw new RuntimeException("Failed to save IP session", error);
    }
  }

  private static int updateSession(Connection connection, IpSession session) throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(
        "UPDATE starx_ip_sessions SET login_time=?, isp=COALESCE(?, isp), "
            + "location=COALESCE(?, location), source=?, device_fingerprint=? "
            + "WHERE player_uuid=? AND ip_address=?")) {
      update.setLong(1, session.loginTime());
      update.setString(2, session.isp());
      update.setString(3, session.location());
      update.setString(4, session.source());
      update.setString(5, session.deviceFingerprint());
      update.setString(6, session.uuid().toString());
      update.setString(7, session.ipAddress());
      return update.executeUpdate();
    }
  }

  private static void insertSession(Connection connection, IpSession session) throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO starx_ip_sessions "
            + "(player_uuid, ip_address, isp, location, login_time, source, device_fingerprint) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, session.uuid().toString());
      insert.setString(2, session.ipAddress());
      insert.setString(3, session.isp());
      insert.setString(4, session.location());
      insert.setLong(5, session.loginTime());
      insert.setString(6, session.source());
      insert.setString(7, session.deviceFingerprint());
      insert.executeUpdate();
    }
  }

  /**
   * 根据玩家 UUID 和 IP 地址查找会话
   */
  public Optional<IpSession> findByUuidAndIp(UUID uuid, String ipAddress) {
    String sql = "SELECT " + SELECT_COLUMNS + " FROM starx_ip_sessions WHERE player_uuid = ? AND ip_address = ?";
    return queryOne(sql, ps -> {
      ps.setString(1, uuid.toString());
      ps.setString(2, ipAddress);
    }, this::map);
  }

  /**
   * 查找玩家最近 N 小时内有登录记录的 IP
   */
  public List<IpSession> findRecentSessions(UUID uuid, int hours) {
    long cutoff = System.currentTimeMillis() - (hours * 60L * 60L * 1000L);
    String sql = "SELECT " + SELECT_COLUMNS + """
        FROM starx_ip_sessions
        WHERE player_uuid = ? AND login_time > ?
        ORDER BY login_time DESC
        """;
    return queryList(sql, ps -> {
      ps.setString(1, uuid.toString());
      ps.setLong(2, cutoff);
    }, this::map);
  }

  /**
   * 检查是否存在有效的 IP 免密记录
   */
  public boolean hasRecentSession(UUID uuid, String ipAddress, int hours) {
    Optional<IpSession> session = findByUuidAndIp(uuid, ipAddress);
    return session.isPresent() && session.get().isWithinHours(hours);
  }

  /**
   * 删除玩家的所有 IP 会话记录
   */
  public void deleteByUuid(UUID uuid) {
    execute("DELETE FROM starx_ip_sessions WHERE player_uuid = ?", ps -> ps.setString(1, uuid.toString()));
  }

  /**
   * 删除指定 IP 的记录
   */
  public void deleteByUuidAndIp(UUID uuid, String ipAddress) {
    execute("DELETE FROM starx_ip_sessions WHERE player_uuid = ? AND ip_address = ?", ps -> {
      ps.setString(1, uuid.toString());
      ps.setString(2, ipAddress);
    });
  }

  /**
   * 获取玩家的最新登录会话
   */
  public Optional<IpSession> findLatestByUuid(UUID uuid) {
    String sql = "SELECT " + SELECT_COLUMNS + " FROM starx_ip_sessions WHERE player_uuid = ? ORDER BY login_time DESC LIMIT 1";
    return queryOne(sql, ps -> ps.setString(1, uuid.toString()), this::map);
  }

  /**
   * 获取玩家的所有登录来源类型
   */
  public List<String> findAllSourcesByUuid(UUID uuid) {
    String sql = "SELECT DISTINCT source FROM starx_ip_sessions WHERE player_uuid = ?";
    return queryList(sql, ps -> ps.setString(1, uuid.toString()), rs -> rs.getString("source"));
  }

  private IpSession map(ResultSet rs) throws SQLException {
    return new IpSession(
        UUID.fromString(rs.getString("player_uuid")),
        rs.getString("ip_address"),
        rs.getString("isp"),
        rs.getString("location"),
        rs.getLong("login_time"),
        rs.getString("source"),
        rs.getString("device_fingerprint")
    );
  }

  private <T> Optional<T> queryOne(String sql, ParamBinder binder, RowMapper<T> mapper) {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new RuntimeException("Query failed: " + sql, e);
    }
  }

  private <T> List<T> queryList(String sql, ParamBinder binder, RowMapper<T> mapper) {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      binder.bind(ps);
      List<T> results = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          results.add(mapper.map(rs));
        }
      }
      return results;
    } catch (SQLException e) {
      throw new RuntimeException("Query failed: " + sql, e);
    }
  }

  private void execute(String sql, ParamBinder binder) {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      binder.bind(ps);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Execute failed: " + sql, e);
    }
  }

  @FunctionalInterface
  private interface ParamBinder {
    void bind(PreparedStatement ps) throws SQLException;
  }

  @FunctionalInterface
  private interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }
}
