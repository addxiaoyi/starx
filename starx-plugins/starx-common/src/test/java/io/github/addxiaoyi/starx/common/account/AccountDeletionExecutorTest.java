package io.github.addxiaoyi.starx.common.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class AccountDeletionExecutorTest {
  @TempDir Path tempDir;

  @Test
  void claimsOnlyDueRequestsAndCompletesAfterErasure() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("executor.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
    }
    JdbcAccountDeletionRepository deletions = new JdbcAccountDeletionRepository(source);
    UUID player = UUID.randomUUID();
    String requestId = deletions.request(player, 1_000L, 8_000L);
    AtomicReference<UUID> erased = new AtomicReference<>();

    AccountDeletionExecutor.ExecutionSummary summary = new AccountDeletionExecutor(deletions,
        (uuid, now) -> erased.set(uuid)).runOnce(8_000L);

    assertEquals(1, summary.completed());
    assertEquals(player, erased.get());
    assertTrue(deletions.findDue(8_000L, 10).isEmpty());
    assertEquals("COMPLETED", state(source, requestId));
  }

  @Test
  void claimReleaseFailureDoesNotEscapeTheRetryLoop() throws Exception {
    org.sqlite.SQLiteDataSource delegate = new org.sqlite.SQLiteDataSource();
    delegate.setUrl("jdbc:sqlite:" + tempDir.resolve("release-failure.db").toAbsolutePath());
    try (Connection connection = delegate.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
    }
    String requestId = new JdbcAccountDeletionRepository(delegate)
        .request(UUID.randomUUID(), 1_000L, 8_000L);
    AtomicInteger connections = new AtomicInteger();
    DataSource failing = new DataSource() {
      @Override public Connection getConnection() throws SQLException {
        if (connections.incrementAndGet() >= 5) throw new SQLException("database unavailable");
        return delegate.getConnection();
      }
      @Override public Connection getConnection(String user, String password) throws SQLException {
        return getConnection();
      }
      @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("unsupported"); }
      @Override public boolean isWrapperFor(Class<?> type) { return false; }
      @Override public java.io.PrintWriter getLogWriter() { return null; }
      @Override public void setLogWriter(java.io.PrintWriter out) { }
      @Override public void setLoginTimeout(int seconds) { }
      @Override public int getLoginTimeout() { return 0; }
      @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
    };

    AccountDeletionExecutor.ExecutionSummary summary = new AccountDeletionExecutor(
        new JdbcAccountDeletionRepository(failing), (uuid, now) -> {
          throw new IllegalStateException("erase failed");
        }).runOnce(8_000L);

    assertEquals(1, summary.claimed());
    assertEquals(1, summary.failures().size());
    assertEquals(requestId, summary.failures().get(0).requestId());
  }

  private static String state(SQLiteDataSource source, String requestId) throws Exception {
    try (Connection connection = source.getConnection(); var query = connection.prepareStatement(
        "SELECT state FROM starx_account_deletions WHERE request_id = ?")) {
      query.setString(1, requestId);
      try (var rows = query.executeQuery()) {
        rows.next();
        return rows.getString(1);
      }
    }
  }
}
