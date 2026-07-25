package io.github.addxiaoyi.starx.common.binding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcBindingChallengeRepository {
  public static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS starx_binding_challenges (challenge_id VARCHAR(36) PRIMARY KEY, account_id VARCHAR(64) NOT NULL, kind VARCHAR(24) NOT NULL, payload VARCHAR(512), token_hash VARCHAR(128) NOT NULL UNIQUE, state VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL, sent_at BIGINT, confirmed_at BIGINT, consumed_at BIGINT, expires_at BIGINT NOT NULL, revoked_at BIGINT, FOREIGN KEY (account_id) REFERENCES starx_accounts(account_id))";

  private final DataSource dataSource;
  private final BindingStateMachine stateMachine = new BindingStateMachine();

  public JdbcBindingChallengeRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public String create(String accountId, String kind, String tokenHash, long createdAt, long expiresAt) {
    return create(accountId, kind, null, tokenHash, createdAt, expiresAt);
  }

  public String create(
      String accountId, String kind, String payload, String tokenHash, long createdAt, long expiresAt) {
    if (expiresAt <= createdAt) throw new IllegalArgumentException("expiresAt must be after createdAt");
    String id = UUID.randomUUID().toString();
    String sql = "INSERT INTO starx_binding_challenges (challenge_id, account_id, kind, payload, token_hash, state, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    try (Connection connection = dataSource.getConnection(); PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setString(1, id);
      insert.setString(2, requireText(accountId, "accountId"));
      insert.setString(3, requireText(kind, "kind"));
      insert.setString(4, payload);
      insert.setString(5, requireText(tokenHash, "tokenHash"));
      insert.setString(6, BindingState.CREATED.name());
      insert.setLong(7, createdAt);
      insert.setLong(8, expiresAt);
      insert.executeUpdate();
      return id;
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to create binding challenge", error);
    }
  }

  public boolean transition(String id, BindingState expected, BindingAction action, long at) {
    BindingState next = stateMachine.move(expected, action);
    String timestampColumn = action == BindingAction.RELEASE ? null : switch (next) {
      case SENT -> "sent_at";
      case CONFIRMED -> "confirmed_at";
      case CONSUMED -> "consumed_at";
      case REVOKED -> "revoked_at";
      case EXPIRED, CREATED -> null;
    };
    boolean terminal = next == BindingState.CONSUMED
        || next == BindingState.REVOKED
        || next == BindingState.EXPIRED;
    StringBuilder sql = new StringBuilder("UPDATE starx_binding_challenges SET state = ?");
    if (action == BindingAction.RELEASE) sql.append(", confirmed_at = NULL");
    if (terminal) sql.append(", token_hash = ?");
    if (timestampColumn != null) sql.append(", ").append(timestampColumn).append(" = ?");
    sql.append(" WHERE challenge_id = ? AND state = ?");
    boolean requiresActiveChallenge = action == BindingAction.CONFIRM
        || action == BindingAction.CONSUME;
    if (requiresActiveChallenge) sql.append(" AND expires_at >= ?");
    if (action == BindingAction.EXPIRE) sql.append(" AND expires_at < ?");
    try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(sql.toString())) {
      update.setString(1, next.name());
      int offset = 2;
      if (terminal) update.setString(offset++, "terminal:" + id + ":" + at);
      if (timestampColumn != null) update.setLong(offset++, at);
      update.setString(offset++, id);
      update.setString(offset++, expected.name());
      if (requiresActiveChallenge || action == BindingAction.EXPIRE) update.setLong(offset, at);
      return update.executeUpdate() == 1;
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to transition binding challenge", error);
    }
  }

  public Optional<BindingChallenge> find(String id) {
    String sql = "SELECT challenge_id, account_id, kind, payload, token_hash, state, created_at, expires_at FROM starx_binding_challenges WHERE challenge_id = ?";
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, id);
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(new BindingChallenge(
            row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5),
            BindingState.valueOf(row.getString(6)), row.getLong(7), row.getLong(8)));
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to load binding challenge", error);
    }
  }

  public Optional<BindingChallenge> findSent(String kind, String tokenHash) {
    String sql = "SELECT challenge_id, account_id, kind, payload, token_hash, state, created_at, expires_at "
        + "FROM starx_binding_challenges WHERE kind = ? AND token_hash = ? AND state = ? "
        + "ORDER BY created_at DESC LIMIT 1";
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, requireText(kind, "kind"));
      query.setString(2, requireText(tokenHash, "tokenHash"));
      query.setString(3, BindingState.SENT.name());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(new BindingChallenge(
            row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5),
            BindingState.valueOf(row.getString(6)), row.getLong(7), row.getLong(8)));
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to find binding challenge", error);
    }
  }

  private static String requireText(String value, String field) {
    String text = Objects.requireNonNull(value, field).trim();
    if (text.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return text;
  }
}
