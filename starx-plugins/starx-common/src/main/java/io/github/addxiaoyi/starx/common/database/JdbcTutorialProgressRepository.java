package io.github.addxiaoyi.starx.common.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcTutorialProgressRepository {
  public static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS starx_tutorial_progress (player_uuid VARCHAR(36) PRIMARY KEY, step INT NOT NULL, updated_at BIGINT NOT NULL)";
  private static final int MAX_CAS_ATTEMPTS = 16;

  private final DataSource dataSource;

  public JdbcTutorialProgressRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public int step(UUID playerId) {
    return step(List.of(playerId));
  }

  public int step(Collection<UUID> playerIds) {
    List<UUID> ids = JdbcUuidQuery.distinct(playerIds);
    if (ids.isEmpty()) return 0;
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
        "SELECT MAX(step) FROM starx_tutorial_progress WHERE player_uuid IN ("
            + JdbcUuidQuery.placeholders(ids.size()) + ")")) {
      JdbcUuidQuery.bind(query, ids);
      try (ResultSet row = query.executeQuery()) {
        return row.next() ? Math.max(0, row.getInt(1)) : 0;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to load tutorial progress", error);
    }
  }

  public int advance(UUID playerId, int stepCount, long updatedAt) {
    return advance(playerId, List.of(playerId), stepCount, updatedAt);
  }

  public int advance(UUID canonicalPlayerId, Collection<UUID> knownPlayerIds, int stepCount, long updatedAt) {
    Objects.requireNonNull(canonicalPlayerId, "canonicalPlayerId");
    List<UUID> ids = JdbcUuidQuery.distinct(knownPlayerIds);
    if (ids.isEmpty()) return 0;
    if (stepCount < 1) throw new IllegalArgumentException("stepCount must be positive");
    for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
      int current = step(ids);
      if (current >= stepCount) return stepCount;
      int canonicalStep = step(canonicalPlayerId);
      int next = Math.min(stepCount, Math.max(current, canonicalStep) + 1);
      if (canonicalStep == 0 && insertFirst(canonicalPlayerId, next, updatedAt)) return next;
      if (updateExpected(canonicalPlayerId, canonicalStep, next, updatedAt)) return next;
    }
    throw new IllegalStateException("Tutorial progress changed too frequently; retry the command");
  }

  public void reset(UUID playerId) {
    reset(List.of(playerId));
  }

  public void reset(Collection<UUID> playerIds) {
    List<UUID> ids = JdbcUuidQuery.distinct(playerIds);
    if (ids.isEmpty()) return;
    try (Connection connection = dataSource.getConnection(); PreparedStatement delete = connection.prepareStatement(
        "DELETE FROM starx_tutorial_progress WHERE player_uuid IN ("
            + JdbcUuidQuery.placeholders(ids.size()) + ")")) {
      JdbcUuidQuery.bind(delete, ids);
      delete.executeUpdate();
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to reset tutorial progress", error);
    }
  }

  public int complete(UUID playerId, int stepCount, long updatedAt) {
    return complete(playerId, List.of(playerId), stepCount, updatedAt);
  }

  public int complete(UUID canonicalPlayerId, Collection<UUID> knownPlayerIds, int stepCount, long updatedAt) {
    Objects.requireNonNull(canonicalPlayerId, "canonicalPlayerId");
    List<UUID> ids = JdbcUuidQuery.distinct(knownPlayerIds);
    if (ids.isEmpty()) return 0;
    if (stepCount < 1) throw new IllegalArgumentException("stepCount must be positive");
    int existingStep = step(ids);
    if (existingStep >= stepCount) return existingStep;
    if (completeExisting(canonicalPlayerId, stepCount, updatedAt)) {
      return Math.max(stepCount, step(ids));
    }
    if (insertFirst(canonicalPlayerId, Math.max(stepCount, existingStep), updatedAt)) {
      return Math.max(stepCount, step(ids));
    }
    if (completeExisting(canonicalPlayerId, stepCount, updatedAt)) {
      return Math.max(stepCount, step(ids));
    }
    return Math.max(stepCount, step(ids));
  }

  private boolean insertFirst(UUID playerId, int step, long updatedAt) {
    try (Connection connection = dataSource.getConnection(); PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO starx_tutorial_progress (player_uuid, step, updated_at) VALUES (?, ?, ?)")) {
      insert.setString(1, playerId.toString());
      insert.setInt(2, step);
      insert.setLong(3, updatedAt);
      return insert.executeUpdate() == 1;
    } catch (SQLException error) {
      if (isConstraintViolation(error)) return false;
      throw new IllegalStateException("Failed to insert tutorial progress", error);
    }
  }

  private static boolean isConstraintViolation(SQLException error) {
    for (SQLException current = error; current != null; current = current.getNextException()) {
      String state = current.getSQLState();
      if ("23505".equals(state)) return true;
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("unique") || normalized.contains("duplicate")) return true;
      }
    }
    return false;
  }

  private boolean updateExpected(UUID playerId, int expected, int next, long updatedAt) {
    try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(
        "UPDATE starx_tutorial_progress SET step = ?, updated_at = ? WHERE player_uuid = ? AND step = ?")) {
      update.setInt(1, next);
      update.setLong(2, updatedAt);
      update.setString(3, playerId.toString());
      update.setInt(4, expected);
      return update.executeUpdate() == 1;
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to advance tutorial progress", error);
    }
  }

  private boolean completeExisting(UUID playerId, int stepCount, long updatedAt) {
    try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(
        "UPDATE starx_tutorial_progress SET step = ?, updated_at = ? WHERE player_uuid = ? AND step < ?")) {
      update.setInt(1, stepCount);
      update.setLong(2, updatedAt);
      update.setString(3, playerId.toString());
      update.setInt(4, stepCount);
      return update.executeUpdate() == 1;
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to complete tutorial progress", error);
    }
  }
}
