package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.model.StaffVote;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
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
    return this.store.many(
        "SELECT * FROM starx_staff_votes WHERE initiator_uuid = ? ORDER BY created_at DESC",
        statement -> statement.setString(1, initiatorId.toString()),
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
    this.store.transaction(connection -> {
      JdbcStore.execute(
          connection,
          "INSERT INTO starx_staff_vote_records (vote_id, voter_uuid, vote, voted_at) VALUES (?, ?, ?, ?)",
          statement -> {
            statement.setString(1, voteId);
            statement.setString(2, voterId.toString());
            statement.setString(3, yes ? "YES" : "NO");
            statement.setLong(4, System.currentTimeMillis());
          });
      String column = yes ? "yes_votes" : "no_votes";
      JdbcStore.execute(
          connection,
          "UPDATE starx_staff_votes SET " + column + " = " + column + " + 1 WHERE id = ?",
          statement -> statement.setString(1, voteId));
    });
  }

  public boolean hasVoted(String voteId, UUID voterId) {
    return this.store.one(
        "SELECT 1 FROM starx_staff_vote_records WHERE vote_id = ? AND voter_uuid = ?",
        statement -> {
          statement.setString(1, voteId);
          statement.setString(2, voterId.toString());
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
