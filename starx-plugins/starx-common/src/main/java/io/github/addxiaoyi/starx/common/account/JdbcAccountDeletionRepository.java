package io.github.addxiaoyi.starx.common.account;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcAccountDeletionRepository {
  public static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS starx_account_deletions (request_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, state VARCHAR(16) NOT NULL, requested_at BIGINT NOT NULL, execute_after BIGINT NOT NULL, cancelled_at BIGINT, claimed_at BIGINT, completed_at BIGINT)";
  private final DataSource dataSource;

  public JdbcAccountDeletionRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public String request(UUID playerUuid, long requestedAt, long executeAfter) {
    if (executeAfter <= requestedAt) throw new IllegalArgumentException("executeAfter must be after requestedAt");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String existing = pending(connection, playerUuid);
        if (existing != null) { connection.commit(); return existing; }
        String id = UUID.randomUUID().toString();
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO starx_account_deletions (request_id, player_uuid, state, requested_at, execute_after) VALUES (?, ?, 'PENDING', ?, ?)")) {
          insert.setString(1, id); insert.setString(2, playerUuid.toString());
          insert.setLong(3, requestedAt); insert.setLong(4, executeAfter); insert.executeUpdate();
        }
        connection.commit();
        return id;
      } catch (SQLException error) { connection.rollback(); throw error; }
    } catch (SQLException error) { throw failure("request", error); }
  }

  public boolean cancel(String requestId, UUID playerUuid, long cancelledAt) {
    Objects.requireNonNull(playerUuid, "playerUuid");
    return update("UPDATE starx_account_deletions SET state='CANCELLED', cancelled_at=? "
            + "WHERE request_id=? AND player_uuid=? AND state='PENDING'",
        cancelledAt, requestId, playerUuid.toString());
  }

  public boolean claimDue(String requestId, long now) {
    return update("UPDATE starx_account_deletions SET state='CLAIMED', claimed_at=? WHERE request_id=? AND state='PENDING' AND execute_after<=?",
        now, requestId, now);
  }

  public java.util.List<DueRequest> findDue(long now, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
        "SELECT request_id, player_uuid FROM starx_account_deletions WHERE state='PENDING' AND execute_after<=? ORDER BY execute_after, requested_at LIMIT ?")) {
      query.setLong(1, now);
      query.setInt(2, limit);
      try (ResultSet rows = query.executeQuery()) {
        java.util.List<DueRequest> due = new java.util.ArrayList<>();
        while (rows.next()) due.add(new DueRequest(rows.getString(1), UUID.fromString(rows.getString(2))));
        return java.util.List.copyOf(due);
      }
    } catch (SQLException error) {
      throw failure("find due account deletions", error);
    }
  }

  public boolean complete(String requestId, long completedAt) {
    return update("UPDATE starx_account_deletions SET state='COMPLETED', completed_at=? WHERE request_id=? AND state='CLAIMED'",
        completedAt, requestId);
  }

  public boolean releaseClaim(String requestId) {
    return update("UPDATE starx_account_deletions SET state='PENDING', claimed_at=NULL WHERE request_id=? AND state='CLAIMED'",
        requestId);
  }

  public int releaseStaleClaims(long now, long leaseMillis) {
    if (leaseMillis < 1L) throw new IllegalArgumentException("leaseMillis must be positive");
    long cutoff = now - leaseMillis;
    try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(
        "UPDATE starx_account_deletions SET state='PENDING', claimed_at=NULL "
            + "WHERE state='CLAIMED' AND claimed_at IS NOT NULL AND claimed_at<=?")) {
      update.setLong(1, cutoff);
      return update.executeUpdate();
    } catch (SQLException error) {
      throw failure("release stale claims", error);
    }
  }

  public Optional<RequestStatus> latest(UUID playerUuid) {
    Objects.requireNonNull(playerUuid, "playerUuid");
    String sql = "SELECT request_id, player_uuid, state, requested_at, execute_after, cancelled_at, claimed_at, completed_at FROM starx_account_deletions WHERE player_uuid=? ORDER BY requested_at DESC LIMIT 1";
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, playerUuid.toString());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(new RequestStatus(
            row.getString(1),
            UUID.fromString(row.getString(2)),
            row.getString(3),
            row.getLong(4),
            row.getLong(5),
            nullableLong(row, 6),
            nullableLong(row, 7),
            nullableLong(row, 8)));
      }
    } catch (SQLException error) {
      throw failure("load latest account deletion", error);
    }
  }

  private String pending(Connection connection, UUID playerUuid) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT request_id FROM starx_account_deletions WHERE player_uuid=? AND state='PENDING' ORDER BY requested_at DESC LIMIT 1")) {
      query.setString(1, playerUuid.toString());
      try (ResultSet row = query.executeQuery()) { return row.next() ? row.getString(1) : null; }
    }
  }

  private boolean update(String sql, Object... values) {
    try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) update.setObject(i + 1, values[i]);
      return update.executeUpdate() == 1;
    } catch (SQLException error) { throw failure("update", error); }
  }

  private IllegalStateException failure(String action, SQLException error) {
    return new IllegalStateException("Failed to " + action + " account deletion", error);
  }

  private static Long nullableLong(ResultSet row, int column) throws SQLException {
    long value = row.getLong(column);
    return row.wasNull() ? null : value;
  }

  public record DueRequest(String requestId, UUID playerUuid) {}

  public record RequestStatus(
      String requestId,
      UUID playerUuid,
      String state,
      long requestedAt,
      long executeAfter,
      Long cancelledAt,
      Long claimedAt,
      Long completedAt) {}
}
