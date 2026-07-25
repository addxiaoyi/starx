package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

final class JdbcUserRepositoryTotpTest {

  @TempDir
  Path tempDir;

  private SQLiteDataSource source;
  private JdbcUserRepository users;
  private UUID playerId;

  @BeforeEach
  void setUp() throws Exception {
    this.source = new SQLiteDataSource();
    this.source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("totp.db").toAbsolutePath());
    this.playerId = UUID.randomUUID();
    try (Connection connection = this.source.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE starx_users (
            uuid VARCHAR(36) PRIMARY KEY,
            totp_secret VARCHAR(255),
            recovery_codes VARCHAR(512)
          )
          """);
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO starx_users (uuid, totp_secret, recovery_codes) VALUES (?, ?, ?)")) {
        insert.setString(1, this.playerId.toString());
        insert.setString(2, null);
        insert.setString(3, null);
        insert.executeUpdate();
      }
    }
    this.users = new JdbcUserRepository(this.source);
  }

  @Test
  void enablesTotpWithOneAtomicDatabaseUpdate() throws Exception {
    assertTrue(this.users.enableTotp(this.playerId, "new-secret", "new-codes"));
    assertFalse(this.users.enableTotp(this.playerId, "second-secret", "second-codes"));

    assertEquals(new TotpColumns("new-secret", "new-codes"), this.readColumns());
  }

  @Test
  void failedRecoveryCodeWriteDoesNotLeaveANewSecretBehind() throws Exception {
    try (Connection connection = this.source.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TRIGGER reject_recovery_codes
          BEFORE UPDATE OF recovery_codes ON starx_users
          WHEN NEW.recovery_codes = 'reject'
          BEGIN
            SELECT RAISE(ABORT, 'forced recovery code failure');
          END
          """);
    }

    assertThrows(
        RuntimeException.class,
        () -> this.users.enableTotp(this.playerId, "new-secret", "reject"));

    assertEquals(new TotpColumns(null, null), this.readColumns());
  }

  @Test
  void disableClearsSecretAndRecoveryCodesAtomically() throws Exception {
    assertTrue(this.users.enableTotp(this.playerId, "new-secret", "new-codes"));

    assertTrue(this.users.disableTotp(this.playerId));
    assertFalse(this.users.disableTotp(this.playerId));
    assertEquals(new TotpColumns(null, null), this.readColumns());
  }

  private TotpColumns readColumns() throws Exception {
    try (Connection connection = this.source.getConnection();
         PreparedStatement query = connection.prepareStatement(
             "SELECT totp_secret, recovery_codes FROM starx_users WHERE uuid = ?")) {
      query.setString(1, this.playerId.toString());
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next());
        return new TotpColumns(rows.getString(1), rows.getString(2));
      }
    }
  }

  private record TotpColumns(String secret, String recoveryCodes) {
  }
}
