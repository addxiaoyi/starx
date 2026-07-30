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
  public static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS starx_binding_challenges (challenge_id VARCHAR(36) PRIMARY KEY, account_id VARCHAR(64) NOT NULL, kind VARCHAR(24) NOT NULL, payload VARCHAR(512), token_hash VARCHAR(128) NOT NULL UNIQUE, state VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL, sent_at BIGINT, confirmed_at BIGINT, consumed_at BIGINT, expires_at BIGINT NOT NULL, revoked_at BIGINT, execution_owner VARCHAR(36), execution_lease_until BIGINT, FOREIGN KEY (account_id) REFERENCES starx_accounts(account_id))";

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
    if (action == BindingAction.RELEASE || terminal) {
      sql.append(", execution_owner = NULL, execution_lease_until = NULL");
    }
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

  public boolean acquireExecution(
      String id, String owner, long now, long leaseUntil) {
    if (leaseUntil <= now) throw new IllegalArgumentException("leaseUntil must be after now");
    String sql = "UPDATE starx_binding_challenges SET state = ?, "
        + "confirmed_at = COALESCE(confirmed_at, ?), execution_owner = ?, execution_lease_until = ? "
        + "WHERE challenge_id = ? AND ((state = ? AND expires_at >= ?) "
        + "OR (state = ? AND (execution_lease_until IS NULL OR execution_lease_until <= ?)))";
    try (Connection connection = dataSource.getConnection();
         PreparedStatement update = connection.prepareStatement(sql)) {
      update.setString(1, BindingState.CONFIRMED.name());
      update.setLong(2, now);
      update.setString(3, requireText(owner, "owner"));
      update.setLong(4, leaseUntil);
      update.setString(5, requireText(id, "id"));
      update.setString(6, BindingState.SENT.name());
      update.setLong(7, now);
      update.setString(8, BindingState.CONFIRMED.name());
      update.setLong(9, now);
      return update.executeUpdate() == 1;
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to acquire binding challenge execution", error);
    }
  }

  public boolean completeExecution(
      String id, String owner, BindingAction action, long at) {
    if (action != BindingAction.CONSUME && action != BindingAction.RELEASE) {
      throw new IllegalArgumentException("Execution completion must consume or release");
    }
    boolean consume = action == BindingAction.CONSUME;
    String sql = consume
        ? "UPDATE starx_binding_challenges SET state = ?, token_hash = ?, consumed_at = ?, execution_owner = NULL, execution_lease_until = NULL WHERE challenge_id = ? AND state = ? AND execution_owner = ?"
        : "UPDATE starx_binding_challenges SET state = ?, confirmed_at = NULL, execution_owner = NULL, execution_lease_until = NULL WHERE challenge_id = ? AND state = ? AND execution_owner = ?";
    try (Connection connection = dataSource.getConnection();
         PreparedStatement update = connection.prepareStatement(sql)) {
      int offset = 1;
      update.setString(offset++, consume ? BindingState.CONSUMED.name() : BindingState.SENT.name());
      if (consume) {
        update.setString(offset++, "terminal:" + id + ":" + at);
        update.setLong(offset++, at);
      }
      update.setString(offset++, requireText(id, "id"));
      update.setString(offset++, BindingState.CONFIRMED.name());
      update.setString(offset, requireText(owner, "owner"));
      if (update.executeUpdate() == 1) return true;
    } catch (SQLException error) {
      if (consume && isConsumed(id)) return true;
      throw new IllegalStateException("Failed to complete binding challenge execution", error);
    }
    return consume && isConsumed(id);
  }

  public Optional<BindingChallenge> find(String id) {
    String sql = "SELECT challenge_id, account_id, kind, payload, token_hash, state, created_at, expires_at FROM starx_binding_challenges WHERE challenge_id = ?";
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, id);
      try (ResultSet row = query.executeQuery()) {
        return row.next() ? Optional.of(map(row)) : Optional.empty();
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to load binding challenge", error);
    }
  }

  public Optional<BindingChallenge> findSent(String kind, String tokenHash) {
    return findByStates(kind, tokenHash, BindingState.SENT.name());
  }

  public Optional<BindingChallenge> findExecutable(String kind, String tokenHash) {
    return findByStates(
        kind, tokenHash, BindingState.SENT.name(), BindingState.CONFIRMED.name());
  }

  private Optional<BindingChallenge> findByStates(
      String kind, String tokenHash, String... states) {
    String placeholders = String.join(", ", java.util.Collections.nCopies(states.length, "?"));
    String sql = "SELECT challenge_id, account_id, kind, payload, token_hash, state, created_at, expires_at "
        + "FROM starx_binding_challenges WHERE kind = ? AND token_hash = ? AND state IN ("
        + placeholders + ") ORDER BY created_at DESC LIMIT 1";
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, requireText(kind, "kind"));
      query.setString(2, requireText(tokenHash, "tokenHash"));
      for (int index = 0; index < states.length; index++) query.setString(index + 3, states[index]);
      try (ResultSet row = query.executeQuery()) {
        return row.next() ? Optional.of(map(row)) : Optional.empty();
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to find binding challenge", error);
    }
  }

  private boolean isConsumed(String id) {
    return find(id).map(challenge -> challenge.state() == BindingState.CONSUMED).orElse(false);
  }

  private static BindingChallenge map(ResultSet row) throws SQLException {
    return new BindingChallenge(
        row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5),
        BindingState.valueOf(row.getString(6)), row.getLong(7), row.getLong(8));
  }

  private static String requireText(String value, String field) {
    String text = Objects.requireNonNull(value, field).trim();
    if (text.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return text;
  }
}
