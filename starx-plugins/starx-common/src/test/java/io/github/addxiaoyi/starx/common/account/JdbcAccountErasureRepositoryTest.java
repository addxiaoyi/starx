package io.github.addxiaoyi.starx.common.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.addxiaoyi.starx.common.binding.JdbcBindingChallengeRepository;
import io.github.addxiaoyi.starx.common.identity.JdbcAccountIdentityRepository;
import io.github.addxiaoyi.starx.common.session.JdbcPlayerSessionRepository;
import io.github.addxiaoyi.starx.common.database.JdbcTutorialProgressRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcAccountErasureRepositoryTest {
  @TempDir Path tempDir;

  @Test
  void erasesAllHistoricalUuidsAcrossStarxData() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("erasure.db").toAbsolutePath()
        + "?foreign_keys=on");
    UUID player = UUID.randomUUID();
    UUID historical = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("PRAGMA foreign_keys = ON");
      sql.execute("CREATE TABLE starx_users (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(255), email VARCHAR(255))");
      sql.execute(JdbcAccountIdentityRepository.CREATE_ACCOUNTS_SQL);
      sql.execute(JdbcAccountIdentityRepository.CREATE_IDENTITIES_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SESSIONS_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SEGMENTS_SQL);
      sql.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_announcement_reads (announcement_id TEXT, player_uuid TEXT, read_at BIGINT, PRIMARY KEY (announcement_id, player_uuid))");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_player_bindings (player_uuid VARCHAR(36) PRIMARY KEY, qq_id VARCHAR(64), discord_id VARCHAR(64), created_at BIGINT NOT NULL)");
      sql.execute("CREATE TABLE starx_website_bindings (player_uuid VARCHAR(36) PRIMARY KEY, website_user_id VARCHAR(64), verified_at BIGINT)");
      sql.execute("CREATE TABLE starx_binding_audit (audit_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, binding_kind VARCHAR(16), action VARCHAR(16), actor VARCHAR(128), occurred_at BIGINT)");
      sql.execute("CREATE TABLE starx_trusted_devices (id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, fingerprint_hash VARCHAR(64), region_key VARCHAR(128), label VARCHAR(128), first_seen_at BIGINT, last_seen_at BIGINT, expires_at BIGINT, revoked_at BIGINT)");
      sql.execute("CREATE TABLE starx_ip_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid VARCHAR(36) NOT NULL, ip_address VARCHAR(45), isp VARCHAR(255), location VARCHAR(255), login_time BIGINT, source VARCHAR(16))");
      sql.execute("CREATE TABLE starx_staff_vote_records (vote_id VARCHAR(36), voter_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_votes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), initiator_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_reports (id VARCHAR(36) PRIMARY KEY, reporter_uuid VARCHAR(36), target_uuid VARCHAR(36), resolved_by VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_notes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_punishments (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      insert(connection, "INSERT INTO starx_users(uuid, username, email) VALUES (?, ?, ?)", player, "player", "player@example.test");
      insert(connection, "INSERT INTO starx_users(uuid, username, email) VALUES (?, ?, ?)", historical, "historical", "historical@example.test");
      insert(connection, "INSERT INTO starx_users(uuid, username, email) VALUES (?, ?, ?)", other, "other", "other@example.test");
      insert(connection, "INSERT INTO starx_accounts(account_id, created_at) VALUES (?, ?)", "account-player", 1L);
      insert(connection, "INSERT INTO starx_account_identities(account_id, minecraft_uuid, identity_source, current_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)", "account-player", player, "OFFLINE", "player", 1L, 1L);
      insert(connection, "INSERT INTO starx_account_identities(account_id, minecraft_uuid, identity_source, current_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)", "account-player", historical, "MOJANG", "historical", 2L, 2L);
      insert(connection, "INSERT INTO starx_player_sessions(session_id, player_uuid, started_at) VALUES (?, ?, ?)", "session-player", player, 1L);
      insert(connection, "INSERT INTO starx_player_sessions(session_id, player_uuid, started_at) VALUES (?, ?, ?)", "session-historical", historical, 1L);
      insert(connection, "INSERT INTO starx_player_server_segments(segment_id, session_id, player_uuid, server_name, started_at) VALUES (?, ?, ?, ?, ?)", "segment-player", "session-player", player, "hub", 1L);
      insert(connection, "INSERT INTO starx_tutorial_progress(player_uuid, step, updated_at) VALUES (?, ?, ?)", player, 2, 1L);
      insert(connection, "INSERT INTO starx_binding_challenges(challenge_id, account_id, kind, token_hash, state, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)", "challenge-player", "account-player", "EMAIL", "hash-player", "CREATED", 1L, 2L);
      insert(connection, "INSERT INTO starx_player_bindings(player_uuid, qq_id, created_at) VALUES (?, ?, ?)", player, "123", 1L);
      insert(connection, "INSERT INTO starx_website_bindings(player_uuid, website_user_id, verified_at) VALUES (?, ?, ?)", player, "website-player", 1L);
      insert(connection, "INSERT INTO starx_binding_audit(audit_id, player_uuid, action) VALUES (?, ?, ?)", "audit-player", player, "BIND");
      insert(connection, "INSERT INTO starx_trusted_devices(id, player_uuid) VALUES (?, ?)", "device-player", player);
      insert(connection, "INSERT INTO starx_ip_sessions(player_uuid, ip_address) VALUES (?, ?)", player, "127.0.0.1");
    }

    assertEquals(1, new JdbcAccountErasureRepository(source).erase(player, 9_000L));
    assertEquals(0, count(source, "starx_users", "uuid", player));
    assertEquals(0, count(source, "starx_users", "uuid", historical));
    assertEquals(1, count(source, "starx_users", "uuid", other));
    assertEquals(0, count(source, "starx_account_identities", "minecraft_uuid", player));
    assertEquals(0, count(source, "starx_accounts", "account_id", "account-player"));
    assertEquals(0, count(source, "starx_player_sessions", "player_uuid", player));
    assertEquals(0, count(source, "starx_player_sessions", "player_uuid", historical));
    assertEquals(0, count(source, "starx_player_server_segments", "player_uuid", player));
    assertEquals(0, count(source, "starx_tutorial_progress", "player_uuid", player));
    assertEquals(0, count(source, "starx_binding_challenges", "challenge_id", "challenge-player"));
    assertEquals(0, count(source, "starx_player_bindings", "player_uuid", player));
    assertEquals(0, count(source, "starx_website_bindings", "player_uuid", player));
    assertEquals(0, count(source, "starx_trusted_devices", "player_uuid", player));
    assertEquals(0, count(source, "starx_ip_sessions", "player_uuid", player));
  }

  @Test
  void erasesLegacyProfileDataWhenTheIdentityWasMigratedToMojangUuid() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("migrated-erasure.db").toAbsolutePath()
        + "?foreign_keys=on");
    UUID online = UUID.fromString("5d7d6a4a-cb9b-4a07-a1e7-baf4a5c4b7a3");
    UUID legacy = UUID.nameUUIDFromBytes(
        ("OfflinePlayer:LegacyName").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("PRAGMA foreign_keys = ON");
      sql.execute("CREATE TABLE starx_users (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(255), email VARCHAR(255))");
      sql.execute(JdbcAccountIdentityRepository.CREATE_ACCOUNTS_SQL);
      sql.execute(JdbcAccountIdentityRepository.CREATE_IDENTITIES_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SESSIONS_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SEGMENTS_SQL);
      sql.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_announcement_reads (announcement_id TEXT, player_uuid TEXT, read_at BIGINT, PRIMARY KEY (announcement_id, player_uuid))");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_player_bindings (player_uuid VARCHAR(36) PRIMARY KEY, qq_id VARCHAR(64), discord_id VARCHAR(64), created_at BIGINT NOT NULL)");
      sql.execute("CREATE TABLE starx_website_bindings (player_uuid VARCHAR(36) PRIMARY KEY, website_user_id VARCHAR(64), verified_at BIGINT)");
      sql.execute("CREATE TABLE starx_binding_audit (audit_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, binding_kind VARCHAR(16), action VARCHAR(16), actor VARCHAR(128), occurred_at BIGINT)");
      sql.execute("CREATE TABLE starx_trusted_devices (id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, fingerprint_hash VARCHAR(64), region_key VARCHAR(128), label VARCHAR(128), first_seen_at BIGINT, last_seen_at BIGINT, expires_at BIGINT, revoked_at BIGINT)");
      sql.execute("CREATE TABLE starx_ip_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid VARCHAR(36) NOT NULL, ip_address VARCHAR(45), isp VARCHAR(255), location VARCHAR(255), login_time BIGINT, source VARCHAR(16))");
      sql.execute("CREATE TABLE starx_staff_vote_records (vote_id VARCHAR(36), voter_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_votes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), initiator_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_reports (id VARCHAR(36) PRIMARY KEY, reporter_uuid VARCHAR(36), target_uuid VARCHAR(36), resolved_by VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_notes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_punishments (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      insert(connection, "INSERT INTO starx_users(uuid, username, email) VALUES (?, ?, ?)", legacy, "LegacyName", "legacy@example.test");
      insert(connection, "INSERT INTO starx_accounts(account_id, created_at) VALUES (?, ?)", "mc:" + legacy, 1L);
      insert(connection, "INSERT INTO starx_account_identities(account_id, minecraft_uuid, identity_source, current_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)", "mc:" + legacy, legacy, "OFFLINE", "RenamedLegacy", 1L, 2L);
      insert(connection, "INSERT INTO starx_account_identities(account_id, minecraft_uuid, identity_source, current_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)", "mc:" + legacy, online, "MOJANG", "CurrentName", 2L, 1L);
      insert(connection, "INSERT INTO starx_player_sessions(session_id, player_uuid, started_at) VALUES (?, ?, ?)", "session-legacy", legacy, 1L);
      insert(connection, "INSERT INTO starx_tutorial_progress(player_uuid, step, updated_at) VALUES (?, ?, ?)", legacy, 2, 1L);
      insert(connection, "INSERT INTO starx_player_bindings(player_uuid, qq_id, created_at) VALUES (?, ?, ?)", legacy, "123", 1L);
      insert(connection, "INSERT INTO starx_binding_challenges(challenge_id, account_id, kind, token_hash, state, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)", "challenge-legacy", "mc:" + legacy, "EMAIL", "hash-legacy", "CREATED", 1L, 2L);
    }

    assertEquals(1, new JdbcAccountErasureRepository(source).erase(online, 9_000L));
    assertEquals(0, count(source, "starx_users", "uuid", legacy));
    assertEquals(0, count(source, "starx_account_identities", "minecraft_uuid", online));
    assertEquals(0, count(source, "starx_accounts", "account_id", "mc:" + legacy));
    assertEquals(0, count(source, "starx_player_sessions", "player_uuid", legacy));
    assertEquals(0, count(source, "starx_tutorial_progress", "player_uuid", legacy));
    assertEquals(0, count(source, "starx_player_bindings", "player_uuid", legacy));
    assertEquals(0, count(source, "starx_binding_challenges", "challenge_id", "challenge-legacy"));
  }

  @Test
  void erasesLegacyProfileDataWhenTheIdentityWasMigratedToFloodgateUuid() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("floodgate-erasure.db").toAbsolutePath()
        + "?foreign_keys=on");
    UUID floodgate = UUID.fromString("8a5f0b1c-6e2d-4f7b-9a31-2c4d6e8f0b12");
    UUID legacy = UUID.nameUUIDFromBytes(
        ("OfflinePlayer:FloodgateLegacy").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("PRAGMA foreign_keys = ON");
      sql.execute("CREATE TABLE starx_users (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(255), email VARCHAR(255))");
      sql.execute(JdbcAccountIdentityRepository.CREATE_ACCOUNTS_SQL);
      sql.execute(JdbcAccountIdentityRepository.CREATE_IDENTITIES_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SESSIONS_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SEGMENTS_SQL);
      sql.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_announcement_reads (announcement_id TEXT, player_uuid TEXT, read_at BIGINT, PRIMARY KEY (announcement_id, player_uuid))");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_player_bindings (player_uuid VARCHAR(36) PRIMARY KEY, qq_id VARCHAR(64), discord_id VARCHAR(64), created_at BIGINT NOT NULL)");
      sql.execute("CREATE TABLE starx_website_bindings (player_uuid VARCHAR(36) PRIMARY KEY, website_user_id VARCHAR(64), verified_at BIGINT)");
      sql.execute("CREATE TABLE starx_binding_audit (audit_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, binding_kind VARCHAR(16), action VARCHAR(16), actor VARCHAR(128), occurred_at BIGINT)");
      sql.execute("CREATE TABLE starx_trusted_devices (id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, fingerprint_hash VARCHAR(64), region_key VARCHAR(128), label VARCHAR(128), first_seen_at BIGINT, last_seen_at BIGINT, expires_at BIGINT, revoked_at BIGINT)");
      sql.execute("CREATE TABLE starx_ip_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid VARCHAR(36) NOT NULL, ip_address VARCHAR(45), isp VARCHAR(255), location VARCHAR(255), login_time BIGINT, source VARCHAR(16))");
      sql.execute("CREATE TABLE starx_staff_vote_records (vote_id VARCHAR(36), voter_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_votes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), initiator_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_reports (id VARCHAR(36) PRIMARY KEY, reporter_uuid VARCHAR(36), target_uuid VARCHAR(36), resolved_by VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_notes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_punishments (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      insert(connection, "INSERT INTO starx_users(uuid, username, email) VALUES (?, ?, ?)", legacy, "FloodgateLegacy", "legacy@example.test");
      insert(connection, "INSERT INTO starx_accounts(account_id, created_at) VALUES (?, ?)", "mc:" + legacy, 1L);
      insert(connection, "INSERT INTO starx_account_identities(account_id, minecraft_uuid, identity_source, current_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)", "mc:" + legacy, floodgate, "FLOODGATE", "FloodgateLegacy", 1L, 1L);
      insert(connection, "INSERT INTO starx_player_sessions(session_id, player_uuid, started_at) VALUES (?, ?, ?)", "session-legacy", legacy, 1L);
    }

    assertEquals(1, new JdbcAccountErasureRepository(source).erase(floodgate, 9_000L));
    assertEquals(0, count(source, "starx_users", "uuid", legacy));
    assertEquals(0, count(source, "starx_account_identities", "minecraft_uuid", floodgate));
    assertEquals(0, count(source, "starx_accounts", "account_id", "mc:" + legacy));
    assertEquals(0, count(source, "starx_player_sessions", "player_uuid", legacy));
  }

  @Test
  void erasesOrphanedMinecraftAccountRowsWithoutAnIdentityRecord() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("orphan-account-erasure.db").toAbsolutePath()
        + "?foreign_keys=on");
    UUID player = UUID.randomUUID();
    String accountId = "mc:" + player;
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("PRAGMA foreign_keys = ON");
      sql.execute("CREATE TABLE starx_users (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(255), email VARCHAR(255))");
      sql.execute(JdbcAccountIdentityRepository.CREATE_ACCOUNTS_SQL);
      sql.execute(JdbcAccountIdentityRepository.CREATE_IDENTITIES_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SESSIONS_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SEGMENTS_SQL);
      sql.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_announcement_reads (announcement_id TEXT, player_uuid TEXT, read_at BIGINT, PRIMARY KEY (announcement_id, player_uuid))");
      sql.execute("CREATE TABLE starx_player_bindings (player_uuid VARCHAR(36) PRIMARY KEY, qq_id VARCHAR(64), discord_id VARCHAR(64), created_at BIGINT NOT NULL)");
      sql.execute("CREATE TABLE starx_website_bindings (player_uuid VARCHAR(36) PRIMARY KEY, website_user_id VARCHAR(64), verified_at BIGINT)");
      sql.execute("CREATE TABLE starx_binding_audit (audit_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, action VARCHAR(16))");
      sql.execute("CREATE TABLE starx_trusted_devices (id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL)");
      sql.execute("CREATE TABLE starx_ip_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid VARCHAR(36) NOT NULL)");
      sql.execute("CREATE TABLE starx_staff_vote_records (vote_id VARCHAR(36), voter_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_votes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), initiator_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_reports (id VARCHAR(36) PRIMARY KEY, reporter_uuid VARCHAR(36), target_uuid VARCHAR(36), resolved_by VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_notes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_punishments (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      insert(connection, "INSERT INTO starx_accounts(account_id, created_at) VALUES (?, ?)", accountId, 1L);
      insert(connection,
          "INSERT INTO starx_binding_challenges(challenge_id, account_id, kind, token_hash, state, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
          "orphan-challenge", accountId, "EMAIL", "orphan-hash", "CREATED", 1L, 2L);
      insert(connection, "INSERT INTO starx_users(uuid, username) VALUES (?, ?)", player, "Orphan");
    }

    assertEquals(1, new JdbcAccountErasureRepository(source).erase(player, 9_000L));
    assertEquals(0, count(source, "starx_users", "uuid", player));
    assertEquals(0, count(source, "starx_binding_challenges", "challenge_id", "orphan-challenge"));
    assertEquals(0, count(source, "starx_accounts", "account_id", accountId));
  }

  @Test
  void rollsBackErasureWhenTheClaimTokenCannotCompleteTheRequest() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("erasure-completion-rollback.db").toAbsolutePath());
    UUID player = UUID.randomUUID();
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_users (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(255))");
      sql.execute(JdbcAccountIdentityRepository.CREATE_ACCOUNTS_SQL);
      sql.execute(JdbcAccountIdentityRepository.CREATE_IDENTITIES_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SESSIONS_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SEGMENTS_SQL);
      sql.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_announcement_reads (announcement_id TEXT, player_uuid TEXT)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_player_bindings (player_uuid VARCHAR(36) PRIMARY KEY)");
      sql.execute("CREATE TABLE starx_website_bindings (player_uuid VARCHAR(36) PRIMARY KEY)");
      sql.execute("CREATE TABLE starx_binding_audit (audit_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_trusted_devices (id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_ip_sessions (id INTEGER PRIMARY KEY, player_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_vote_records (vote_id VARCHAR(36), voter_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_votes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), initiator_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_reports (id VARCHAR(36) PRIMARY KEY, reporter_uuid VARCHAR(36), target_uuid VARCHAR(36), resolved_by VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_notes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_punishments (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      insert(connection, "INSERT INTO starx_users(uuid, username) VALUES (?, ?)", player, "RollbackPlayer");
    }
    JdbcAccountDeletionRepository deletions = new JdbcAccountDeletionRepository(source);
    String requestId = deletions.request(player, 1_000L, 2_000L);
    deletions.claimDueToken(requestId, 2_000L).orElseThrow();

    assertThrows(IllegalStateException.class, () -> new JdbcAccountErasureRepository(source)
        .eraseAndComplete(deletions, requestId, "stale-claim-token", player, 3_000L));

    assertEquals(1, count(source, "starx_users", "uuid", player));
    assertEquals("CLAIMED", deletions.latest(player).orElseThrow().state());
  }

  @Test
  void directErasureCompletesPendingDeletionBeforeRemovingAccountData() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("direct-erasure-completes-pending.db").toAbsolutePath());
    UUID player = UUID.randomUUID();
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_users (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(255))");
      sql.execute(JdbcAccountIdentityRepository.CREATE_ACCOUNTS_SQL);
      sql.execute(JdbcAccountIdentityRepository.CREATE_IDENTITIES_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SESSIONS_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SEGMENTS_SQL);
      sql.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_announcement_reads (announcement_id TEXT, player_uuid TEXT)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TABLE starx_player_bindings (player_uuid VARCHAR(36) PRIMARY KEY)");
      sql.execute("CREATE TABLE starx_website_bindings (player_uuid VARCHAR(36) PRIMARY KEY)");
      sql.execute("CREATE TABLE starx_binding_audit (audit_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_trusted_devices (id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_ip_sessions (id INTEGER PRIMARY KEY, player_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_vote_records (vote_id VARCHAR(36), voter_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_votes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), initiator_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_reports (id VARCHAR(36) PRIMARY KEY, reporter_uuid VARCHAR(36), target_uuid VARCHAR(36), resolved_by VARCHAR(36))");
      sql.execute("CREATE TABLE starx_staff_notes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      sql.execute("CREATE TABLE starx_punishments (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), staff_uuid VARCHAR(36))");
      insert(connection, "INSERT INTO starx_users(uuid, username) VALUES (?, ?)", player, "DirectErase");
    }
    JdbcAccountDeletionRepository deletions = new JdbcAccountDeletionRepository(source);
    String requestId = deletions.request(player, 1_000L, 9_000L);

    new JdbcAccountErasureRepository(source)
        .eraseAndCompletePending(deletions, player, Set.of(player), 3_000L);

    assertEquals(0, count(source, "starx_users", "uuid", player));
    assertEquals("COMPLETED", deletions.latest(player).orElseThrow().state());
    assertEquals(requestId, deletions.latest(player).orElseThrow().requestId());
  }

  private static void insert(Connection connection, String sql, Object... values) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        Object value = values[index];
        statement.setObject(index + 1, value instanceof UUID uuid ? uuid.toString() : value);
      }
      statement.executeUpdate();
    }
  }

  private static int count(SQLiteDataSource source, String table, String column, Object value) throws Exception {
    try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
      query.setObject(1, value instanceof UUID uuid ? uuid.toString() : value);
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }
}
