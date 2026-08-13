package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

final class JdbcUserRepositoryExternalIdentityTest {
  @TempDir Path tempDir;

  private SQLiteDataSource source;
  private JdbcUserRepository users;
  private UUID firstPlayer;
  private UUID secondPlayer;

  @BeforeEach
  void setUp() throws Exception {
    this.source = new SQLiteDataSource();
    this.source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("external-identity.db").toAbsolutePath());
    this.firstPlayer = UUID.randomUUID();
    this.secondPlayer = UUID.randomUUID();
    try (Connection connection = this.source.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE starx_users ("
          + "uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(255), email VARCHAR(255), "
          + "password_hash VARCHAR(255), totp_secret VARCHAR(255), premium BOOLEAN, "
          + "created_at TIMESTAMP, last_login_at TIMESTAMP, external_user_id VARCHAR(100), "
          + "trusted_devices TEXT, recovery_codes VARCHAR(512), source_system VARCHAR(50), "
          + "migration_state VARCHAR(20), password_migrated_at TIMESTAMP, last_login_ip VARCHAR(255), "
          + "last_login_isp VARCHAR(255), last_login_location VARCHAR(255), total_playtime BIGINT, "
          + "last_logout_at TIMESTAMP, welcome_message_shown BOOLEAN)");
      statement.execute("CREATE TABLE starx_website_bindings (player_uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(16) NOT NULL, external_user_id VARCHAR(100) NOT NULL, verified BOOLEAN NOT NULL, updated_at BIGINT NOT NULL)");
      insertUser(connection, this.firstPlayer, null);
      insertUser(connection, this.secondPlayer, null);
    }
    this.users = new JdbcUserRepository(this.source);
  }

  @Test
  void linksExternalIdentityAndWebsiteBindingTogether() throws Exception {
    this.users.linkExternalIdentity(this.firstPlayer, "Alex", "site-user-7", true);

    assertEquals("site-user-7", readUserExternalId(this.firstPlayer));
    assertTrue(hasWebsiteBinding(this.firstPlayer, "site-user-7", true));
  }

  @Test
  void websiteBindingSaveCannotSplitAnExternalIdentityAcrossPlayers() throws Exception {
    this.users.linkExternalIdentity(this.firstPlayer, "Alex", "site-user-7", true);

    assertThrows(
        JdbcUserRepository.ExternalIdentityConflictException.class,
        () -> this.users.saveWebsiteBinding(this.secondPlayer, "Steve", "site-user-7", true));

    assertEquals(null, readUserExternalId(this.secondPlayer));
    assertFalse(hasWebsiteBinding(this.secondPlayer, "site-user-7", true));
  }

  @Test
  void rejectsAnExternalIdentityAlreadyOwnedByAnotherPlayer() throws Exception {
    this.users.linkExternalIdentity(this.firstPlayer, "Alex", "site-user-7", true);

    assertThrows(
        IllegalStateException.class,
        () -> this.users.linkExternalIdentity(this.secondPlayer, "Steve", "site-user-7", true));

    assertEquals(null, readUserExternalId(this.secondPlayer));
    assertFalse(hasWebsiteBinding(this.secondPlayer, "site-user-7", true));
  }

  @Test
  void rejectsUniAuthSyncWhenTheExternalIdentityIsAlreadyOwned() throws Exception {
    this.users.linkExternalIdentity(this.firstPlayer, "Alex", "site-user-7", true);

    assertThrows(
        JdbcUserRepository.ExternalIdentityConflictException.class,
        () -> this.users.updateExternalIdentity(this.secondPlayer, "site-user-7", "uniauth"));
  }

  @Test
  void externalIdentityReplacementRevokesTheOldWebsiteBinding() throws Exception {
    this.users.linkExternalIdentity(this.firstPlayer, "Alex", "site-user-7", true);

    this.users.updateExternalIdentity(this.firstPlayer, "site-user-8", "uniauth");

    assertEquals("site-user-8", readUserExternalId(this.firstPlayer));
    assertFalse(hasWebsiteBinding(this.firstPlayer, "site-user-7", true));
  }

  @Test
  void rejectsDtoSaveWhenTheExternalIdentityIsAlreadyOwned() throws Exception {
    this.users.linkExternalIdentity(this.firstPlayer, "Alex", "site-user-7", true);

    UserDto replacement = UserDto.builder()
        .uuid(this.secondPlayer)
        .username("Steve")
        .externalUserId("site-user-7")
        .build();

    assertThrows(
        JdbcUserRepository.ExternalIdentityConflictException.class,
        () -> this.users.save(replacement));

    assertEquals(null, readUserExternalId(this.secondPlayer));
  }

  @Test
  void rejectsDomainSaveWhenTheExternalIdentityIsAlreadyOwned() throws Exception {
    this.users.linkExternalIdentity(this.firstPlayer, "Alex", "site-user-7", true);

    StarxUser replacement = new StarxUser(
        this.secondPlayer, "Steve", null, null, null, false, Instant.now(), null,
        "site-user-7", null, null, null, null, null, null, null, null,
        0L, null, false);

    assertThrows(
        JdbcUserRepository.ExternalIdentityConflictException.class,
        () -> this.users.saveUser(replacement));

    assertEquals(null, readUserExternalId(this.secondPlayer));
  }

  @Test
  void rejectsDomainCreateWhenTheExternalIdentityIsAlreadyOwned() throws Exception {
    this.users.linkExternalIdentity(this.firstPlayer, "Alex", "site-user-7", true);

    StarxUser replacement = new StarxUser(
        this.secondPlayer, "Steve", null, null, null, false, Instant.now(), null,
        "site-user-7", null, null, null, null, null, null, null, null,
        0L, null, false);

    assertThrows(
        JdbcUserRepository.ExternalIdentityConflictException.class,
        () -> this.users.create(replacement));

    assertEquals(null, readUserExternalId(this.secondPlayer));
  }

  @Test
  void rollsBackUserIdentityWhenWebsiteBindingWriteFails() throws Exception {
    try (Connection connection = this.source.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TRIGGER reject_website_binding BEFORE INSERT ON starx_website_bindings BEGIN SELECT RAISE(ABORT, 'forced binding failure'); END");
    }

    assertThrows(
        RuntimeException.class,
        () -> this.users.linkExternalIdentity(this.firstPlayer, "Alex", "site-user-7", true));

    assertEquals(null, readUserExternalId(this.firstPlayer));
    assertFalse(hasWebsiteBinding(this.firstPlayer, "site-user-7", true));
  }

  private void insertUser(Connection connection, UUID uuid, String externalId) throws Exception {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO starx_users (uuid, external_user_id) VALUES (?, ?)")) {
      insert.setString(1, uuid.toString());
      insert.setString(2, externalId);
      insert.executeUpdate();
    }
  }

  private String readUserExternalId(UUID uuid) throws Exception {
    try (Connection connection = this.source.getConnection();
         PreparedStatement query = connection.prepareStatement(
             "SELECT external_user_id FROM starx_users WHERE uuid = ?")) {
      query.setString(1, uuid.toString());
      try (var rows = query.executeQuery()) {
        assertTrue(rows.next());
        return rows.getString(1);
      }
    }
  }

  private boolean hasWebsiteBinding(UUID uuid, String externalId, boolean verified) throws Exception {
    try (Connection connection = this.source.getConnection();
         PreparedStatement query = connection.prepareStatement(
             "SELECT 1 FROM starx_website_bindings WHERE player_uuid = ? AND external_user_id = ? AND verified = ?")) {
      query.setString(1, uuid.toString());
      query.setString(2, externalId);
      query.setBoolean(3, verified);
      try (var rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }
}
