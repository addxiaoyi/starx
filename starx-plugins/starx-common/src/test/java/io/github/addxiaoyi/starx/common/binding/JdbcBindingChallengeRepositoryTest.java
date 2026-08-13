package io.github.addxiaoyi.starx.common.binding;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcBindingChallengeRepositoryTest {
  @TempDir Path tempDir;

  @Test
  void consumesAConfirmedTokenOnlyOnce() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("binding.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    JdbcBindingChallengeRepository repo = new JdbcBindingChallengeRepository(source);
    String id = repo.create("account-1", "EMAIL", "hash", 1L, 10_000L);
    assertTrue(repo.transition(id, BindingState.CREATED, BindingAction.SEND, 2L));
    assertTrue(repo.transition(id, BindingState.SENT, BindingAction.CONFIRM, 3L));
    assertTrue(repo.transition(id, BindingState.CONFIRMED, BindingAction.CONSUME, 4L));
    assertFalse(repo.transition(id, BindingState.CONFIRMED, BindingAction.CONSUME, 5L));
    assertEquals(BindingState.CONSUMED, repo.find(id).orElseThrow().state());
  }

  @Test
  void revokeAndExpireRequireTheExpectedOldState() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("terminal.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    JdbcBindingChallengeRepository repo = new JdbcBindingChallengeRepository(source);
    String revoked = repo.create("account-1", "QQ", "hash-qq", 1L, 10_000L);
    String expired = repo.create("account-1", "SKIN", "hash-skin", 1L, 10_000L);

    assertTrue(repo.transition(revoked, BindingState.CREATED, BindingAction.REVOKE, 2L));
    assertFalse(repo.transition(revoked, BindingState.CREATED, BindingAction.REVOKE, 3L));
    assertTrue(repo.transition(expired, BindingState.CREATED, BindingAction.EXPIRE, 10_001L));
    assertFalse(repo.transition(expired, BindingState.CREATED, BindingAction.EXPIRE, 10_002L));
    assertEquals(BindingState.REVOKED, repo.find(revoked).orElseThrow().state());
    assertEquals(BindingState.EXPIRED, repo.find(expired).orElseThrow().state());
  }

  @Test
  void confirmationAndConsumptionCannotCrossExpiryBoundary() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("expiry-boundary.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    JdbcBindingChallengeRepository repo = new JdbcBindingChallengeRepository(source);
    String confirming = repo.create("account-1", "EMAIL", "hash-confirm", 1L, 10L);
    assertTrue(repo.transition(confirming, BindingState.CREATED, BindingAction.SEND, 2L));
    assertFalse(repo.transition(confirming, BindingState.SENT, BindingAction.EXPIRE, 10L));
    assertFalse(repo.transition(confirming, BindingState.SENT, BindingAction.CONFIRM, 11L));

    String consuming = repo.create("account-1", "QQ", "hash-consume", 1L, 10L);
    assertTrue(repo.transition(consuming, BindingState.CREATED, BindingAction.SEND, 2L));
    assertTrue(repo.transition(consuming, BindingState.SENT, BindingAction.CONFIRM, 3L));
    assertFalse(repo.transition(consuming, BindingState.CONFIRMED, BindingAction.CONSUME, 11L));
  }

  @Test
  void executionCannotBeAcquiredAfterAConfirmedChallengeExpires() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("confirmed-expiry.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    JdbcBindingChallengeRepository repo = new JdbcBindingChallengeRepository(source);
    String id = repo.create("account-1", "EMAIL", "hash-confirmed", 1L, 10L);
    assertTrue(repo.transition(id, BindingState.CREATED, BindingAction.SEND, 2L));
    assertTrue(repo.transition(id, BindingState.SENT, BindingAction.CONFIRM, 3L));

    assertFalse(repo.acquireExecution(id, "owner-1", 11L, 20L));
    assertEquals(BindingState.CONFIRMED, repo.find(id).orElseThrow().state());
  }
}
