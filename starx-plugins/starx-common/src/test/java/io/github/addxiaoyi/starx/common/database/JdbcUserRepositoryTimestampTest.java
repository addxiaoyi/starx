package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

final class JdbcUserRepositoryTimestampTest {

  @TempDir
  Path tempDir;

  private SQLiteDataSource source;

  @BeforeEach
  void setUp() throws Exception {
    this.source = new SQLiteDataSource();
    this.source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("users.db").toAbsolutePath());
    try (Connection connection = this.source.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE starx_users (
            uuid TEXT PRIMARY KEY,
            username TEXT NOT NULL,
            email TEXT,
            password_hash TEXT,
            totp_secret TEXT,
            premium BOOLEAN NOT NULL DEFAULT FALSE,
            created_at,
            last_login_at,
            external_user_id TEXT,
            trusted_devices TEXT,
            recovery_codes TEXT,
            source_system TEXT,
            migration_state TEXT,
            password_migrated_at,
            last_login_ip TEXT,
            last_login_isp TEXT,
            last_login_location TEXT,
            total_playtime BIGINT DEFAULT 0,
            last_logout_at,
            welcome_message_shown BOOLEAN DEFAULT FALSE
          )
          """);
    }
  }

  @Test
  void findAllAcceptsMixedTimestampFormats() throws Exception {
    Instant epochCreatedAt = Instant.parse("2026-03-01T00:00:00Z");
    try (Connection connection = this.source.getConnection();
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          INSERT INTO starx_users (
            uuid, username, premium, created_at, last_login_at, trusted_devices,
            source_system, migration_state, password_migrated_at, total_playtime,
            last_logout_at, welcome_message_shown
          ) VALUES (
            '11111111-1111-4111-8111-111111111111', 'iso-user', 0,
            '2026-02-28T11:17:48Z', '2026-02-28T19:17:48+08:00', '[]',
            'local', 'completed', '2026-02-28 11:18:48.123', 0,
            1772277529000, 0
          )
          """);
      statement.executeUpdate("""
          INSERT INTO starx_users (
            uuid, username, premium, created_at, last_login_at, trusted_devices,
            source_system, migration_state, total_playtime, welcome_message_shown
          ) VALUES (
            '22222222-2222-4222-8222-222222222222', 'epoch-user', 0,
            %d, '2026-03-01 00:00:01.000', '[]',
            'local', 'completed', 0, 0
          )
          """.formatted(epochCreatedAt.toEpochMilli()));
    }

    List<UserDto> users = new JdbcUserRepository(this.source).findAll();
    UserDto isoUser = user(users, "iso-user");
    UserDto epochUser = user(users, "epoch-user");

    assertEquals(Instant.parse("2026-02-28T11:17:48Z"), isoUser.createdAt());
    assertEquals(Instant.parse("2026-02-28T11:17:48Z"), isoUser.lastLoginAt());
    assertEquals(epochCreatedAt, epochUser.createdAt());
    assertEquals(
        Timestamp.valueOf("2026-03-01 00:00:01.000").toInstant(),
        epochUser.lastLoginAt());
  }

  @Test
  void restoresAMigrationOnlyWhileItsPasswordHashIsStillCurrent() throws Exception {
    UUID playerId = UUID.randomUUID();
    try (Connection connection = this.source.getConnection();
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          INSERT INTO starx_users (
            uuid, username, password_hash, premium, created_at, trusted_devices,
            source_system, migration_state, total_playtime, welcome_message_shown
          ) VALUES ('%s', 'migrating-user', 'legacy-hash', 0, 0, '[]',
            'uniauth', 'pending', 0, 0)
          """.formatted(playerId));
    }

    JdbcUserRepository users = new JdbcUserRepository(this.source);
    users.markPasswordMigrated(playerId, "migration-hash", Instant.parse("2026-08-17T00:00:00Z"));

    assertTrue(users.restorePasswordMigrationIfCurrent(
        playerId, "migration-hash", "legacy-hash", "pending", null));
    assertEquals("legacy-hash", users.findFullByUuid(playerId).orElseThrow().passwordHash());

    users.markPasswordMigrated(playerId, "newer-hash", Instant.parse("2026-08-17T00:01:00Z"));
    assertFalse(users.restorePasswordMigrationIfCurrent(
        playerId, "migration-hash", "legacy-hash", "pending", null));
    assertEquals("newer-hash", users.findFullByUuid(playerId).orElseThrow().passwordHash());
  }

  @Test
  void loginMetadataUpdatesReportWhenTheAccountWasDeletedConcurrently() throws Exception {
    UUID playerId = UUID.randomUUID();
    JdbcUserRepository users = new JdbcUserRepository(this.source);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> users.updateLastLogin(playerId, Instant.now()));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> users.updatePremium(playerId, true));
  }

  @Test
  void trustedDeviceReplacementRejectsAStaleSnapshot() throws Exception {
    UUID playerId = UUID.randomUUID();
    JdbcUserRepository users = new JdbcUserRepository(this.source);
    users.create(new io.github.addxiaoyi.starx.common.model.StarxUser(
        playerId, "trusted-user", null, "hash", null, false, Instant.now(), null,
        null, List.of("device-a"), null, "local", "completed", null, null, null,
        null, 0L, null, false));

    assertTrue(users.replaceTrustedDevicesIfCurrent(
        playerId, List.of("device-a"), List.of("device-a", "device-b")));
    assertFalse(users.replaceTrustedDevicesIfCurrent(
        playerId, List.of("device-a"), List.of("device-a", "device-c")));
    assertEquals(List.of("device-a", "device-b"),
        users.findFullByUuid(playerId).orElseThrow().trustedDevices());
  }

  @Test
  void loginMetadataRollbackHandlesAnUnsetPreviousLoginTimestamp() throws Exception {
    UUID playerId = UUID.randomUUID();
    JdbcUserRepository users = new JdbcUserRepository(this.source);
    users.create(new io.github.addxiaoyi.starx.common.model.StarxUser(
        playerId, "new-user", null, "hash", null, false, Instant.now(), null,
        null, List.of(), null, "local", "completed", null, null, null,
        null, 0L, null, false));
    Instant loginAt = Instant.parse("2026-08-17T01:00:00Z");
    users.updateLastLogin(playerId, loginAt);
    users.updatePremium(playerId, true);

    assertTrue(users.restoreLoginMetadataIfCurrent(
        playerId, loginAt, true, null, false));
    assertEquals(null, users.findFullByUuid(playerId).orElseThrow().lastLoginAt());
    assertFalse(users.findFullByUuid(playerId).orElseThrow().premium());
  }

  private static UserDto user(List<UserDto> users, String username) {
    return users.stream()
        .filter(user -> username.equals(user.username()))
        .findFirst()
        .orElseThrow();
  }
}
