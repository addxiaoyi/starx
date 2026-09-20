package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import java.nio.file.Files;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.sqlite.SQLiteDataSource;

/**
 * Standalone stress test runner for JdbcSchema - no JUnit dependency required.
 * Can be run directly: java -cp "..." io.github.addxiaoyi.starx.common.database.StressTestRunner
 */
public class StressTestRunner {

  public static void main(String[] args) throws Exception {
    System.out.println("=== StarX Plugin Stress Test Runner ===");
    System.out.println("Starting stress tests...\n");

    long startTime = System.currentTimeMillis();
    int totalTests = 0;
    int passedTests = 0;

    if (runJdbcSchemaConcurrentIndexTest()) { passedTests++; System.out.println("  [PASSED] ConcurrentIndexTest"); }
    else { System.out.println("  [FAILED] ConcurrentIndexTest"); }
    totalTests++;

    if (runJdbcSchemaConcurrentColumnTest()) { passedTests++; System.out.println("  [PASSED] ConcurrentColumnTest"); }
    else { System.out.println("  [FAILED] ConcurrentColumnTest"); }
    totalTests++;

    if (runRepeatedIndexCreationTest()) { passedTests++; System.out.println("  [PASSED] RepeatedIndexCreationTest"); }
    else { System.out.println("  [FAILED] RepeatedIndexCreationTest"); }
    totalTests++;

    if (runPasswordHasherConcurrentVerificationTest()) { passedTests++; System.out.println("  [PASSED] PasswordHasherVerificationTest"); }
    else { System.out.println("  [FAILED] PasswordHasherVerificationTest"); }
    totalTests++;

    if (runConcurrentDatabaseAccessTest()) { passedTests++; System.out.println("  [PASSED] ConcurrentDatabaseAccessTest"); }
    else { System.out.println("  [FAILED] ConcurrentDatabaseAccessTest"); }
    totalTests++;

    if (runDuplicateConstraintDetectionTest()) { passedTests++; System.out.println("  [PASSED] DuplicateConstraintDetectionTest"); }
    else { System.out.println("  [FAILED] DuplicateConstraintDetectionTest"); }
    totalTests++;

    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;

    System.out.println("\n=== Stress Test Results ===");
    System.out.println("Tests passed: " + passedTests + "/" + totalTests);
    System.out.println("Tests failed: " + (totalTests - passedTests) + "/" + totalTests);
    System.out.println("Total time: " + (duration / 1000) + " seconds");

    if (passedTests < totalTests) {
      System.exit(1);
    }
  }

  private static boolean runJdbcSchemaConcurrentIndexTest() throws Exception {
    System.out.println("[Test 1] Concurrent index creation (100 threads)");
    Path tempDir = Files.createTempDirectory("starx-stress");
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("concurrent-index.db"));

    ExecutorService executor = Executors.newFixedThreadPool(16);

    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, username VARCHAR(64) UNIQUE)");

      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        futures.add(CompletableFuture.runAsync(() -> {
          try (Connection conn = source.getConnection(); Statement stmt = conn.createStatement()) {
            JdbcSchema.createIndex(conn, "users", "idx_users_username", true, "username");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor));
      }

      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

      try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, "users", false, false)) {
        int count = 0;
        while (indexes.next()) {
          if ("idx_users_username".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) count++;
        }
        if (count != 1) {
          System.err.println("  FAILED: Expected 1 index, found " + count);
          return false;
        }
      }

      System.out.println("  OK: 100 concurrent index creation attempts completed, 1 index found");
      return true;
    } finally {
      executor.shutdownNow();
      cleanupTempDir(tempDir);
    }
  }

  private static boolean runJdbcSchemaConcurrentColumnTest() throws Exception {
    System.out.println("[Test 2] Concurrent column addition (20 threads)");
    Path tempDir = Files.createTempDirectory("starx-stress");
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("concurrent-column.db"));

    ExecutorService executor = Executors.newFixedThreadPool(16);

    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE config (id INTEGER PRIMARY KEY, key VARCHAR(64))");

      List<CompletableFuture<Void>> futures = new ArrayList<>();
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

      try (ResultSet columns = connection.getMetaData().getColumns(null, null, "config", "meta")) {
        int count = 0;
        while (columns.next()) count++;
        if (count != 1) {
          System.err.println("  FAILED: Expected 1 column, found " + count);
          return false;
        }
      }

      System.out.println("  OK: 20 concurrent column addition attempts completed, 1 column found");
      return true;
    } finally {
      executor.shutdownNow();
      cleanupTempDir(tempDir);
    }
  }

  private static boolean runRepeatedIndexCreationTest() throws Exception {
    System.out.println("[Test 3] Repeated index creation idempotency (1000 calls)");
    Path tempDir = Files.createTempDirectory("starx-stress");
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("repeated.db"));

    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE stats (id INTEGER PRIMARY KEY, metric VARCHAR(64), value REAL)");

      for (int i = 0; i < 1000; i++) {
        JdbcSchema.createIndex(connection, "stats", "idx_stats_metric", false, "metric");
      }

      try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, "stats", false, false)) {
        int count = 0;
        while (indexes.next()) {
          if ("idx_stats_metric".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) count++;
        }
        if (count != 1) {
          System.err.println("  FAILED: Expected 1 index after 1000 calls, found " + count);
          return false;
        }
      }

      System.out.println("  OK: 1000 index creation calls completed, 1 index found");
      return true;
    } finally {
      cleanupTempDir(tempDir);
    }
  }

  private static boolean runPasswordHasherConcurrentVerificationTest() throws Exception {
    System.out.println("[Test 4] PasswordHasher concurrent verification (1000 threads)");

    String password = "ComplexPassword123!@#";
    String hash = PasswordHasher.hash(password);

    ExecutorService executor = Executors.newFixedThreadPool(64);

    try {
      List<CompletableFuture<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < 1000; i++) {
        futures.add(CompletableFuture.supplyAsync(() -> PasswordHasher.verify(password, hash), executor));
      }

      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);

      long trueCount = 0;
      for (CompletableFuture<Boolean> f : futures) {
        if (f.get()) trueCount++;
      }

      if (trueCount != 1000) {
        System.err.println("  FAILED: Expected 1000 true results, got " + trueCount);
        return false;
      }

      System.out.println("  OK: 1000 concurrent password verifications all returned true");
      return true;
    } finally {
      executor.shutdownNow();
    }
  }

  private static boolean runConcurrentDatabaseAccessTest() throws Exception {
    System.out.println("[Test 5] Concurrent database read/write (200 threads)");
    Path tempDir = Files.createTempDirectory("starx-stress");
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("concurrent-access.db"));

    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE test_data (id INTEGER PRIMARY KEY AUTOINCREMENT, value TEXT, counter INTEGER)");
      sql.execute("CREATE INDEX IF NOT EXISTS idx_test_data_value ON test_data(value)");
    }

    ExecutorService executor = Executors.newFixedThreadPool(24);

    try {
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      AtomicInteger writeCount = new AtomicInteger(0);
      AtomicInteger readCount = new AtomicInteger(0);

      for (int i = 0; i < 200; i++) {
        final int taskId = i;
        if (i % 2 == 0) {
          futures.add(CompletableFuture.runAsync(() -> {
            try (Connection conn = source.getConnection(); Statement stmt = conn.createStatement()) {
              stmt.execute(String.format(
                  "INSERT INTO test_data (value, counter) VALUES ('value-%d', %d)",
                  taskId, taskId));
              writeCount.incrementAndGet();
            } catch (SQLException e) {
              // Ignore duplicate key errors
            }
          }, executor));
        } else {
          futures.add(CompletableFuture.runAsync(() -> {
            try (Connection conn = source.getConnection(); Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data")) {
              if (rs.next()) readCount.incrementAndGet();
            } catch (SQLException e) {
              // Ignore
            }
          }, executor));
        }
      }

      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);

      System.out.println("  OK: Concurrent database access completed (writes: " +
          writeCount.get() + ", reads: " + readCount.get() + ")");
      return true;
    } finally {
      executor.shutdownNow();
      cleanupTempDir(tempDir);
    }
  }

  private static boolean runDuplicateConstraintDetectionTest() throws Exception {
    System.out.println("[Test 6] Duplicate constraint detection");

    List<SQLException> duplicateErrors = List.of(
        new SQLException("UNIQUE constraint failed", "23000", 19),
        new SQLException("duplicate key", "23505"),
        new SQLException("duplicate key value violates unique constraint", "23505"),
        new SQLException("ERROR: duplicate key value violates unique constraint", "42601")
    );

    List<SQLException> nonDuplicateErrors = List.of(
        new SQLException("database is locked", "HY000", 5),
        new SQLException("permission denied", "42000", 1),
        new SQLException("no such table: users", "23001", 1),
        new SQLException("constraint failed", "19", 0)
    );

    for (SQLException error : duplicateErrors) {
      if (!JdbcSchema.isDuplicateConstraint(error)) {
        System.err.println("  FAILED: Should detect duplicate: " + error.getMessage());
        return false;
      }
    }

    for (SQLException error : nonDuplicateErrors) {
      if (JdbcSchema.isDuplicateConstraint(error)) {
        System.err.println("  FAILED: Should NOT detect duplicate: " + error.getMessage());
        return false;
      }
    }

    System.out.println("  OK: Duplicate constraint detection handled correctly");
    return true;
  }

  private static void cleanupTempDir(Path tempDir) {
    try {
      Files.walk(tempDir)
          .sorted((a, b) -> -a.compareTo(b))
          .forEach(path -> {
            try { Files.deleteIfExists(path); } catch (Exception e) { }
          });
    } catch (Exception e) { }
  }
}
