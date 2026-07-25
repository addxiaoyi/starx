package io.github.addxiaoyi.starx.common.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
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
    Objects.requireNonNull(playerId, "playerId");
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
        "SELECT step FROM starx_tutorial_progress WHERE player_uuid = ?")) {
      query.setString(1, playerId.toString());
      try (ResultSet row = query.executeQuery()) {
        return row.next() ? Math.max(0, row.getInt(1)) : 0;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to load tutorial progress", error);
    }
  }

  public int advance(UUID playerId, int stepCount, long updatedAt) {
    Objects.requireNonNull(playerId, "playerId");
    if (stepCount < 1) throw new IllegalArgumentException("stepCount must be positive");
    for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
      int current = step(playerId);
      if (current >= stepCount) return stepCount;
      int next = current + 1;
      if (current == 0 && insertFirst(playerId, next, updatedAt)) return next;
      if (updateExpected(playerId, current, next, updatedAt)) return next;
    }
    throw new IllegalStateException("Tutorial progress changed too frequently; retry the command");
  }

  public void reset(UUID playerId) {
    Objects.requireNonNull(playerId, "playerId");
    try (Connection connection = dataSource.getConnection(); PreparedStatement delete = connection.prepareStatement(
        "DELETE FROM starx_tutorial_progress WHERE player_uuid = ?")) {
      delete.setString(1, playerId.toString());
      delete.executeUpdate();
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to reset tutorial progress", error);
    }
  }

  public int complete(UUID playerId, int stepCount, long updatedAt) {
    Objects.requireNonNull(playerId, "playerId");
    if (stepCount < 1) throw new IllegalArgumentException("stepCount must be positive");
    if (completeExisting(playerId, stepCount, updatedAt)) return stepCount;
    if (insertFirst(playerId, stepCount, updatedAt)) return stepCount;
    if (completeExisting(playerId, stepCount, updatedAt)) return stepCount;
    return Math.max(stepCount, step(playerId));
  }

  private boolean insertFirst(UUID playerId, int step, long updatedAt) {
    try (Connection connection = dataSource.getConnection(); PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO starx_tutorial_progress (player_uuid, step, updated_at) VALUES (?, ?, ?)")) {
      insert.setString(1, playerId.toString());
      insert.setInt(2, step);
      insert.setLong(3, updatedAt);
      return insert.executeUpdate() == 1;
    } catch (SQLException conflict) {
      return false;
    }
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
