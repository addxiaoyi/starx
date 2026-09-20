package io.github.addxiaoyi.starx.common.account;

import io.github.addxiaoyi.starx.common.database.JdbcUuidQuery;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcAccountDeletionRepository {
  public static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS starx_account_deletions (request_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, state VARCHAR(16) NOT NULL, requested_at BIGINT NOT NULL, execute_after BIGINT NOT NULL, cancelled_at BIGINT, claimed_at BIGINT, claim_token VARCHAR(36), completed_at BIGINT)";
  private final DataSource dataSource;

  public JdbcAccountDeletionRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public String request(UUID playerUuid, long requestedAt, long executeAfter) {
    return request(playerUuid, Set.of(playerUuid), requestedAt, executeAfter);
  }

  public String request(
      UUID playerUuid, Set<UUID> knownPlayerUuids, long requestedAt, long executeAfter) {
    Objects.requireNonNull(playerUuid, "playerUuid");
    if (executeAfter <= requestedAt) throw new IllegalArgumentException("executeAfter must be after requestedAt");
    List<UUID> knownUuids = normalizeUuids(knownPlayerUuids);
    try (Connection connection = dataSource.getConnection()) {
      boolean sqlite = isSqlite(connection);
      if (sqlite) {
        executeTransactionControl(connection, "BEGIN IMMEDIATE");
      } else {
        connection.setAutoCommit(false);
      }
      try {
        String existing = active(connection, knownUuids);
        if (existing != null) {
          commit(connection, sqlite);
          return existing;
        }
        String id = UUID.randomUUID().toString();
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO starx_account_deletions (request_id, player_uuid, state, requested_at, execute_after) VALUES (?, ?, 'PENDING', ?, ?)")) {
          insert.setString(1, id); insert.setString(2, playerUuid.toString());
          insert.setLong(3, requestedAt); insert.setLong(4, executeAfter); insert.executeUpdate();
        }
        commit(connection, sqlite);
        return id;
      } catch (SQLException error) {
        rollback(connection, sqlite);
        throw error;
      }
    } catch (SQLException error) { throw failure("request", error); }
  }

  public boolean cancel(String requestId, UUID playerUuid, long cancelledAt) {
    return cancel(requestId, Set.of(playerUuid), cancelledAt);
  }

  public boolean cancel(String requestId, Set<UUID> knownPlayerUuids, long cancelledAt) {
    List<UUID> knownUuids = normalizeUuids(knownPlayerUuids);
    String sql = "UPDATE starx_account_deletions SET state='CANCELLED', cancelled_at=? "
        + "WHERE request_id=? AND player_uuid IN ("
        + JdbcUuidQuery.placeholders(knownUuids.size()) + ") AND state='PENDING'";
    try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(sql)) {
      update.setLong(1, cancelledAt);
      update.setString(2, requestId);
      bindUuids(update, knownUuids, 3);
      return update.executeUpdate() == 1;
    } catch (SQLException error) {
      throw failure("cancel account deletion", error);
    }
  }

  public boolean claimDue(String requestId, long now) {
    return claimDueToken(requestId, now).isPresent();
  }

  public Optional<String> claimDueToken(String requestId, long now) {
    String claimToken = UUID.randomUUID().toString();
    boolean claimed = update(
        "UPDATE starx_account_deletions SET state='CLAIMED', claimed_at=?, claim_token=? "
            + "WHERE request_id=? AND state='PENDING' AND execute_after<=?",
        now, claimToken, requestId, now);
    return claimed ? Optional.of(claimToken) : Optional.empty();
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

  public boolean complete(String requestId, String claimToken, long completedAt) {
    return update(
        "UPDATE starx_account_deletions SET state='COMPLETED', completed_at=?, claim_token=NULL "
            + "WHERE request_id=? AND state='CLAIMED' AND claim_token=?",
        completedAt, requestId, requireToken(claimToken));
  }

  boolean complete(Connection connection, String requestId, String claimToken, long completedAt)
      throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(
        "UPDATE starx_account_deletions SET state='COMPLETED', completed_at=?, claim_token=NULL "
            + "WHERE request_id=? AND state='CLAIMED' AND claim_token=?")) {
      update.setLong(1, completedAt);
      update.setString(2, requestId);
      update.setString(3, requireToken(claimToken));
      return update.executeUpdate() == 1;
    }
  }

  void completePending(Connection connection, Set<UUID> playerUuids, long completedAt)
      throws SQLException {
    List<UUID> uuids = normalizeUuids(playerUuids);
    String sql = "UPDATE starx_account_deletions SET state='COMPLETED', completed_at=? "
        + "WHERE state='PENDING' AND player_uuid IN ("
        + JdbcUuidQuery.placeholders(uuids.size()) + ")";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setLong(1, completedAt);
      bindUuids(update, uuids, 2);
      update.executeUpdate();
    }
  }

  boolean hasClaimed(Connection connection, Set<UUID> playerUuids) throws SQLException {
    List<UUID> uuids = normalizeUuids(playerUuids);
    String sql = "SELECT 1 FROM starx_account_deletions WHERE state='CLAIMED' AND player_uuid IN ("
        + JdbcUuidQuery.placeholders(uuids.size()) + ") LIMIT 1";
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      bindUuids(query, uuids, 1);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }

  public boolean releaseClaim(String requestId, String claimToken) {
    return update(
        "UPDATE starx_account_deletions SET state='PENDING', claimed_at=NULL, claim_token=NULL "
            + "WHERE request_id=? AND state='CLAIMED' AND claim_token=?",
        requestId, requireToken(claimToken));
  }

  public int releaseStaleClaims(long now, long leaseMillis) {
    if (leaseMillis < 1L) throw new IllegalArgumentException("leaseMillis must be positive");
    long cutoff = now - leaseMillis;
    try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(
        "UPDATE starx_account_deletions SET state='PENDING', claimed_at=NULL, claim_token=NULL "
            + "WHERE state='CLAIMED' AND claimed_at IS NOT NULL AND claimed_at<=?")) {
      update.setLong(1, cutoff);
      return update.executeUpdate();
    } catch (SQLException error) {
      throw failure("release stale claims", error);
    }
  }

  public Optional<RequestStatus> latest(UUID playerUuid) {
    return latest(Set.of(playerUuid));
  }

  public Optional<RequestStatus> latest(Set<UUID> knownPlayerUuids) {
    List<UUID> knownUuids = normalizeUuids(knownPlayerUuids);
    String sql = "SELECT request_id, player_uuid, state, requested_at, execute_after, cancelled_at, claimed_at, completed_at "
        + "FROM starx_account_deletions WHERE player_uuid IN ("
        + JdbcUuidQuery.placeholders(knownUuids.size()) + ") ORDER BY requested_at DESC LIMIT 1";
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
      bindUuids(query, knownUuids, 1);
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

  private String active(Connection connection, List<UUID> knownUuids) throws SQLException {
    String sql = "SELECT request_id FROM starx_account_deletions WHERE player_uuid IN ("
        + JdbcUuidQuery.placeholders(knownUuids.size())
        + ") AND state IN ('PENDING', 'CLAIMED') ORDER BY requested_at DESC LIMIT 1";
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      bindUuids(query, knownUuids, 1);
      try (ResultSet row = query.executeQuery()) { return row.next() ? row.getString(1) : null; }
    }
  }

  private static List<UUID> normalizeUuids(Set<UUID> uuids) {
    List<UUID> normalized = JdbcUuidQuery.distinct(Objects.requireNonNull(uuids, "knownPlayerUuids"));
    if (normalized.isEmpty()) throw new IllegalArgumentException("knownPlayerUuids must not be empty");
    return normalized;
  }

  private static void bindUuids(PreparedStatement statement, List<UUID> uuids, int startIndex)
      throws SQLException {
    int index = startIndex;
    for (UUID uuid : uuids) statement.setString(index++, uuid.toString());
  }

  private static boolean isSqlite(Connection connection) throws SQLException {
    String url = connection.getMetaData().getURL();
    return url != null && url.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:sqlite:");
  }

  private static void executeTransactionControl(Connection connection, String sql)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void commit(Connection connection, boolean sqlite) throws SQLException {
    if (sqlite) executeTransactionControl(connection, "COMMIT");
    else connection.commit();
  }

  private static void rollback(Connection connection, boolean sqlite) throws SQLException {
    if (sqlite) executeTransactionControl(connection, "ROLLBACK");
    else connection.rollback();
  }

  private boolean update(String sql, Object... values) {
    try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) update.setObject(i + 1, values[i]);
      return update.executeUpdate() == 1;
    } catch (SQLException error) { throw failure("update", error); }
  }

  private static String requireToken(String claimToken) {
    String token = Objects.requireNonNull(claimToken, "claimToken").trim();
    if (token.isEmpty()) throw new IllegalArgumentException("claimToken must not be blank");
    return token;
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
