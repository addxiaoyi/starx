package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/**
 * Stress test suite for JdbcSchema concurrent operations and database schema integrity.
 *
 * These tests verify the fix for SQLite index creation race conditions and
 * concurrent database access patterns that can occur under plugin startup load.
 */
class JdbcSchemaStressTest {

  @TempDir Path tempDir;

  /**
   * Simulates concurrent plugin initialization where multiple threads
   * attempt to create indexes simultaneously (the root cause of the
   * UNIQUE constraint failure when StarX retries initialization).
   */
  @Test
  void concurrentIndexCreationDoesNotThrowConstraintError() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("concurrent.db").toAbsolutePath());
    ExecutorService executor = Executors.newFixedThreadPool(16);

    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, username VARCHAR(64) UNIQUE)");

      List<CompletableFuture<Void>> futures = new ArrayList<>();

      // Create 100 concurrent tasks, each trying to create the same index
      for (int i = 0; i < 100; i++) {
        futures.add(CompletableFuture.runAsync(() -> {
          try (Connection conn = source.getConnection(); Statement stmt = conn.createStatement()) {
            JdbcSchema.createIndex(conn, "users", "idx_users_username", true, "username");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor));
      }

      // Wait for all tasks to complete (should not throw)
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

      // Verify only one index exists
      try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, "users", false, false)) {
        int count = 0;
        while (indexes.next()) {
          if ("idx_users_username".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) count++;
        }
        assertEquals(1, count, "Index should exist exactly once");
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the IF NOT EXISTS fix works correctly with high-concurrency
   * attempts to create unique indexes that may conflict.
   */
  @Test
  void highConcurrencyUniqueIndexCreation() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("unique_index.db").toAbsolutePath());
    ExecutorService executor = Executors.newFixedThreadPool(8);

    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE sessions (id INTEGER PRIMARY KEY, player_id INTEGER, token VARCHAR(128))");
      sql.execute("CREATE TABLE session_tokens (player_id INTEGER, token VARCHAR(128), UNIQUE(player_id, token))");

      List<CompletableFuture<Void>> futures = new ArrayList<>();

      // Mix of UNIQUE and non-UNIQUE index creation
      for (int i = 0; i < 50; i++) {
        futures.add(CompletableFuture.runAsync(() -> {
          try (Connection conn = source.getConnection(); Statement stmt = conn.createStatement()) {
            // Try to create a UNIQUE index that might conflict with table constraint
            JdbcSchema.createIndex(conn, "session_tokens", "idx_session_tokens_player_id", true, "player_id");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor));

        futures.add(CompletableFuture.runAsync(() -> {
          try (Connection conn = source.getConnection(); Statement stmt = conn.createStatement()) {
            // Regular index
            JdbcSchema.createIndex(conn, "sessions", "idx_sessions_player_id", false, "player_id");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor));
      }

      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent column addition with IF NOT EXISTS semantics.
   */
  @Test
  void concurrentColumnAdditionIsIdempotent() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("columns.db").toAbsolutePath());
    ExecutorService executor = Executors.newFixedThreadPool(16);

    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE config (id INTEGER PRIMARY KEY, key VARCHAR(64))");

      List<CompletableFuture<Void>> futures = new ArrayList<>();

      // Concurrently try to add the same column
      for (int i = 0; i < 20; i++) {
        futures.add(CompletableFuture.runAsync(() -> {
          try (Connection conn = source.getConnection(); Statement stmt = conn.createStatement()) {
            JdbcSchema.addColumnIfMissing(conn, "config", "meta", "TEXT");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor));
      }

      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

      // Verify column exists exactly once
      try (ResultSet columns = connection.getMetaData().getColumns(null, null, "config", "meta")) {
        int count = 0;
        while (columns.next()) count++;
        assertEquals(1, count, "Column should exist exactly once");
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests the isDuplicateConstraint detection under various error conditions.
   */
  @Test
  void duplicateConstraintDetectionHandlesAllKnownErrorCodes() throws Exception {
    // SQLite error codes and SQL states that indicate duplicate key violations
    List<SQLException> duplicateErrors = List.of(
      new SQLException("UNIQUE constraint failed", "23000", 19),
      new SQLException("UNIQUE constraint failed: table users", "23000", 19),
      new SQLException("duplicate key", "23505"),
      new SQLException("duplicate key value violates unique constraint", "23505"),
      new SQLException("ERROR: duplicate key value violates unique constraint", "42601")
    );

    // Errors that should NOT be detected as duplicate constraints
    List<SQLException> nonDuplicateErrors = List.of(
      new SQLException("database is locked", "HY000", 5),
      new SQLException("permission denied", "42000", 1),
      new SQLException("no such table: users", "23001", 1),
      new SQLException("constraint failed", "19", 0)  // SQLite generic constraint (not UNIQUE)
    );

    for (SQLException error : duplicateErrors) {
      assertTrue(JdbcSchema.isDuplicateConstraint(error),
        "Should detect duplicate constraint: " + error.getMessage());
    }

    for (SQLException error : nonDuplicateErrors) {
      assertFalse(JdbcSchema.isDuplicateConstraint(error),
        "Should NOT detect duplicate constraint: " + error.getMessage());
    }
  }

  /**
   * Tests that repeated calls to createIndex with identical parameters
   * are safely idempotent under stress.
   */
  @Test
  void repeatedIndexCreationIsSafe() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("repeated.db").toAbsolutePath());

    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE stats (id INTEGER PRIMARY KEY, metric VARCHAR(64), value REAL)");

      // Call createIndex 1000 times rapidly
      for (int i = 0; i < 1000; i++) {
        JdbcSchema.createIndex(connection, "stats", "idx_stats_metric", false, "metric");
      }

      try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, "stats", false, false)) {
        int count = 0;
        while (indexes.next()) {
          if ("idx_stats_metric".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) count++;
        }
        assertEquals(1, count, "Index should exist exactly once after 1000 create attempts");
      }
    }
  }
}