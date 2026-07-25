package io.github.addxiaoyi.starx.common.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcPlayerSessionRepository {
  public static final String CREATE_SESSIONS_SQL = "CREATE TABLE IF NOT EXISTS starx_player_sessions (session_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, started_at BIGINT NOT NULL, ended_at BIGINT, disconnect_reason VARCHAR(24), last_server VARCHAR(64))";
  public static final String CREATE_SEGMENTS_SQL = "CREATE TABLE IF NOT EXISTS starx_player_server_segments (segment_id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL, server_name VARCHAR(64) NOT NULL, started_at BIGINT NOT NULL, ended_at BIGINT, FOREIGN KEY (session_id) REFERENCES starx_player_sessions(session_id))";

  private final DataSource dataSource;
  public JdbcPlayerSessionRepository(DataSource dataSource) { this.dataSource = dataSource; }

  public String start(UUID player, String server, long at) {
    String sessionId = UUID.randomUUID().toString();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(connection, "INSERT INTO starx_player_sessions (session_id, player_uuid, started_at, last_server) VALUES (?, ?, ?, ?)", sessionId, player.toString(), at, server);
        insertSegment(connection, sessionId, player, server, at);
        connection.commit();
        return sessionId;
      } catch (SQLException error) { connection.rollback(); throw error; }
    } catch (SQLException error) { throw failure("start", error); }
  }

  public void transition(String sessionId, String server, long at) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        UUID player = player(connection, sessionId);
        closeOpenSegment(connection, sessionId, at);
        insertSegment(connection, sessionId, player, server, at);
        execute(connection, "UPDATE starx_player_sessions SET last_server = ? WHERE session_id = ? AND ended_at IS NULL", server, sessionId);
        connection.commit();
      } catch (SQLException error) { connection.rollback(); throw error; }
    } catch (SQLException error) { throw failure("transition", error); }
  }

  public void finish(String sessionId, long at, DisconnectReason reason) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement update = connection.prepareStatement(
          "UPDATE starx_player_sessions SET ended_at = ?, disconnect_reason = ? WHERE session_id = ? AND ended_at IS NULL")) {
        update.setLong(1, at); update.setString(2, reason.name()); update.setString(3, sessionId);
        if (update.executeUpdate() == 1) closeOpenSegment(connection, sessionId, at);
        connection.commit();
      } catch (SQLException error) { connection.rollback(); throw error; }
    } catch (SQLException error) { throw failure("finish", error); }
  }

  public Optional<String> activeSession(UUID player) {
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
        "SELECT session_id FROM starx_player_sessions WHERE player_uuid = ? AND ended_at IS NULL ORDER BY started_at DESC LIMIT 1")) {
      query.setString(1, player.toString());
      try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(row.getString(1)) : Optional.empty(); }
    } catch (SQLException error) { throw failure("find active", error); }
  }

  public Optional<PlayerSessionSummary> summary(UUID player) {
    String sql = "SELECT COALESCE(SUM(CASE WHEN ended_at IS NOT NULL THEN ended_at-started_at ELSE 0 END),0), COUNT(*), MAX(last_server), MAX(disconnect_reason) FROM starx_player_sessions WHERE player_uuid = ?";
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, player.toString());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next() || row.getInt(2) == 0) return Optional.empty();
        String reason = row.getString(4);
        return Optional.of(new PlayerSessionSummary(row.getLong(1), row.getInt(2), row.getString(3), reason == null ? DisconnectReason.UNKNOWN : DisconnectReason.valueOf(reason)));
      }
    } catch (SQLException error) { throw failure("summarize", error); }
  }

  public long playtime(UUID player, String server) {
    String sql = "SELECT COALESCE(SUM(CASE WHEN ended_at IS NOT NULL THEN ended_at-started_at ELSE 0 END),0) FROM starx_player_server_segments WHERE player_uuid = ? AND server_name = ?";
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, player.toString()); query.setString(2, server);
      try (ResultSet row = query.executeQuery()) { return row.next() ? row.getLong(1) : 0L; }
    } catch (SQLException error) { throw failure("read playtime", error); }
  }

  public Map<String, Long> playtimeByServer(UUID player) {
    String sql = "SELECT server_name, COALESCE(SUM(CASE WHEN ended_at IS NOT NULL THEN ended_at-started_at ELSE 0 END),0) FROM starx_player_server_segments WHERE player_uuid = ? GROUP BY server_name ORDER BY server_name";
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, player.toString());
      try (ResultSet rows = query.executeQuery()) {
        Map<String, Long> totals = new LinkedHashMap<>();
        while (rows.next()) totals.put(rows.getString(1), rows.getLong(2));
        return Map.copyOf(totals);
      }
    } catch (SQLException error) { throw failure("read server playtime", error); }
  }

  private void insertSegment(Connection connection, String sessionId, UUID player, String server, long at) throws SQLException {
    execute(connection, "INSERT INTO starx_player_server_segments (segment_id, session_id, player_uuid, server_name, started_at) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID().toString(), sessionId, player.toString(), server, at);
  }
  private void closeOpenSegment(Connection connection, String sessionId, long at) throws SQLException {
    execute(connection, "UPDATE starx_player_server_segments SET ended_at = ? WHERE session_id = ? AND ended_at IS NULL", at, sessionId);
  }
  private UUID player(Connection connection, String sessionId) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement("SELECT player_uuid FROM starx_player_sessions WHERE session_id = ? AND ended_at IS NULL")) {
      query.setString(1, sessionId); try (ResultSet row = query.executeQuery()) {
        if (!row.next()) throw new IllegalArgumentException("Session is not active");
        return UUID.fromString(row.getString(1));
      }
    }
  }
  private void execute(Connection connection, String sql, Object... values) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      statement.executeUpdate();
    }
  }
  private IllegalStateException failure(String action, SQLException error) { return new IllegalStateException("Failed to " + action + " player session", error); }
}
