package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.model.StaffVote;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class JdbcVoteRepository {

  private final JdbcStore store;

  public JdbcVoteRepository(DataSource source) {
    this.store = new JdbcStore(source);
  }

  public void create(StaffVote vote) {
    Objects.requireNonNull(vote, "vote");
    this.store.execute(
        "INSERT INTO starx_staff_votes (id, target_uuid, target_name, reason, vote_type, status, initiator_uuid, initiator_name, yes_votes, no_votes, required_yes, expires_at, created_at, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        statement -> {
          statement.setString(1, vote.id());
          statement.setString(2, vote.targetUuid().toString());
          statement.setString(3, vote.targetName());
          statement.setString(4, vote.reason());
          statement.setString(5, vote.voteType());
          statement.setString(6, vote.status());
          statement.setString(7, vote.initiatorUuid().toString());
          statement.setString(8, vote.initiatorName());
          statement.setInt(9, vote.yesVotes());
          statement.setInt(10, vote.noVotes());
          statement.setInt(11, vote.requiredYes());
          statement.setLong(12, vote.expiresAt());
          statement.setLong(13, vote.createdAt());
          if (vote.resolvedAt() == null) {
            statement.setNull(14, Types.BIGINT);
          } else {
            statement.setLong(14, vote.resolvedAt());
          }
        });
  }

  public Optional<StaffVote> findById(String id) {
    return this.store.one(
        "SELECT * FROM starx_staff_votes WHERE id = ?",
        statement -> statement.setString(1, id),
        this::map);
  }

  public Optional<StaffVote> findActive() {
    return this.store.one(
        "SELECT * FROM starx_staff_votes WHERE status = 'ACTIVE' AND expires_at > ? ORDER BY created_at DESC LIMIT 1",
        statement -> statement.setLong(1, System.currentTimeMillis()),
        this::map);
  }

  public List<StaffVote> findByInitiator(UUID initiatorId) {
    return findByInitiator(List.of(initiatorId));
  }

  public List<StaffVote> findByInitiator(Collection<UUID> initiatorIds) {
    List<UUID> ids = JdbcUuidQuery.distinct(initiatorIds);
    if (ids.isEmpty()) return List.of();
    return this.store.many(
        "SELECT * FROM starx_staff_votes WHERE initiator_uuid IN ("
            + JdbcUuidQuery.placeholders(ids.size()) + ") ORDER BY created_at DESC",
        statement -> JdbcUuidQuery.bind(statement, ids),
        this::map);
  }

  public List<StaffVote> findAllActive() {
    return this.store.many(
        "SELECT * FROM starx_staff_votes WHERE status = 'ACTIVE' AND expires_at > ? ORDER BY created_at DESC",
        statement -> statement.setLong(1, System.currentTimeMillis()),
        this::map);
  }

  public void updateStatus(String id, String status, Long resolvedAt) {
    this.store.execute(
        "UPDATE starx_staff_votes SET status = ?, resolved_at = ? WHERE id = ?",
        statement -> {
          statement.setString(1, status);
          if (resolvedAt == null) {
            statement.setNull(2, Types.BIGINT);
          } else {
            statement.setLong(2, resolvedAt);
          }
          statement.setString(3, id);
        });
  }

  public void castVote(String voteId, UUID voterId, boolean yes) {
    try {
      this.store.transaction(connection -> {
        long now = System.currentTimeMillis();
        int inserted = JdbcStore.execute(
            connection,
            "INSERT INTO starx_staff_vote_records (vote_id, voter_uuid, vote, voted_at) "
                + "SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM starx_staff_votes "
                + "WHERE id = ? AND status = 'ACTIVE' AND expires_at > ?)",
            statement -> {
              statement.setString(1, voteId);
              statement.setString(2, voterId.toString());
              statement.setString(3, yes ? "YES" : "NO");
              statement.setLong(4, now);
              statement.setString(5, voteId);
              statement.setLong(6, now);
            });
        if (inserted != 1) throw new IllegalStateException("Vote is not active or has expired");
        String column = yes ? "yes_votes" : "no_votes";
        int updated = JdbcStore.execute(
            connection,
            "UPDATE starx_staff_votes SET " + column + " = " + column + " + 1 "
                + "WHERE id = ? AND status = 'ACTIVE' AND expires_at > ?",
            statement -> {
              statement.setString(1, voteId);
              statement.setLong(2, now);
            });
        if (updated != 1) throw new IllegalStateException("Vote changed before it could be counted");
      });
    } catch (RuntimeException error) {
      if (isUniqueViolation(error)) {
        throw new VoteAlreadyCastException("Voter has already cast a ballot", error);
      }
      throw error;
    }
  }

  private static boolean isUniqueViolation(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof java.sql.SQLIntegrityConstraintViolationException) return true;
      if (current instanceof java.sql.SQLException sql
          && ("23".equals(sql.getSQLState()) || "23505".equals(sql.getSQLState()))) return true;
      String message = current.getMessage();
      if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("unique constraint")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  public boolean hasVoted(String voteId, UUID voterId) {
    return hasVoted(voteId, List.of(voterId));
  }

  public boolean hasVoted(String voteId, Collection<UUID> voterIds) {
    List<UUID> ids = JdbcUuidQuery.distinct(voterIds);
    if (ids.isEmpty()) return false;
    return this.store.one(
        "SELECT 1 FROM starx_staff_vote_records WHERE vote_id = ? AND voter_uuid IN ("
            + JdbcUuidQuery.placeholders(ids.size()) + ")",
        statement -> {
          statement.setString(1, voteId);
          int index = 2;
          for (UUID id : ids) statement.setString(index++, id.toString());
        },
        rows -> true).isPresent();
  }

  public int countYes(String voteId) {
    return this.store.one(
        "SELECT COUNT(*) FROM starx_staff_vote_records WHERE vote_id = ? AND vote = 'YES'",
        statement -> statement.setString(1, voteId),
        rows -> rows.getInt(1)).orElse(0);
  }

  private StaffVote map(ResultSet rows) throws SQLException {
    long resolvedAt = rows.getLong("resolved_at");
    return new StaffVote(
        rows.getString("id"),
        UUID.fromString(rows.getString("target_uuid")),
        rows.getString("target_name"),
        rows.getString("reason"),
        rows.getString("vote_type"),
        rows.getString("status"),
        UUID.fromString(rows.getString("initiator_uuid")),
        rows.getString("initiator_name"),
        rows.getInt("yes_votes"),
        rows.getInt("no_votes"),
        rows.getInt("required_yes"),
        rows.getLong("expires_at"),
        rows.getLong("created_at"),
        rows.wasNull() ? null : resolvedAt);
  }
}
