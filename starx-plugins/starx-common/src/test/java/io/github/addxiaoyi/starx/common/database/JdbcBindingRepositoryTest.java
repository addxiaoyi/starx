package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

final class JdbcBindingRepositoryTest {

  @TempDir
  Path tempDir;

  private JdbcBindingRepository bindings;

  @BeforeEach
  void setUp() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("bindings.db").toAbsolutePath());
    try (Connection connection = source.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE starx_player_bindings (
            player_uuid VARCHAR(36) PRIMARY KEY,
            qq_id VARCHAR(64),
            discord_id VARCHAR(64),
            created_at BIGINT NOT NULL
          )
          """);
      statement.execute(JdbcBindingRepository.CREATE_AUDIT_TABLE_SQL);
      statement.execute("CREATE UNIQUE INDEX idx_test_binding_qq ON starx_player_bindings(qq_id)");
      statement.execute("CREATE UNIQUE INDEX idx_test_binding_discord ON starx_player_bindings(discord_id)");
    }
    this.bindings = new JdbcBindingRepository(source);
  }

  @Test
  void savesAndFindsBindingByEverySupportedIdentity() {
    UUID playerId = UUID.randomUUID();
    PlayerBinding binding = new PlayerBinding(playerId, "10001", "discord-1", 123L);

    assertTrue(this.bindings.save(binding));

    assertAllFields(binding, this.bindings.findByPlayer(playerId).orElseThrow());
    assertAllFields(binding, this.bindings.findByQq("10001").orElseThrow());
    assertAllFields(binding, this.bindings.findByDiscord("discord-1").orElseThrow());
  }

  @Test
  void missingBindingReturnsEmptyInsteadOfThrowing() {
    assertTrue(this.bindings.findByPlayer(UUID.randomUUID()).isEmpty());
  }

  @Test
  void unbindsOneIdentityAndWritesAnAuditRecord() {
    UUID playerId = UUID.randomUUID();
    assertTrue(this.bindings.save(new PlayerBinding(playerId, "10001", "discord-1", 123L)));

    assertTrue(this.bindings.unbind(playerId, "QQ", "website:user-1", 456L));
    assertFalse(this.bindings.unbind(playerId, "QQ", "website:user-1", 789L));
    PlayerBinding remaining = this.bindings.findByPlayer(playerId).orElseThrow();
    assertEquals(null, remaining.qqId());
    assertEquals("discord-1", remaining.discordId());
    assertEquals(1, this.bindings.auditCount(playerId, "QQ"));
  }

  @Test
  void refusesToBindAnIdentityOwnedByAnotherPlayer() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    assertTrue(this.bindings.save(new PlayerBinding(first, "10001", "discord-1", 1L)));

    assertFalse(this.bindings.save(new PlayerBinding(second, "10001", "discord-2", 2L)));
    assertFalse(this.bindings.save(new PlayerBinding(second, "10002", "discord-1", 2L)));
    assertEquals(first, this.bindings.findByQq("10001").orElseThrow().playerUuid());
    assertTrue(this.bindings.findByPlayer(second).isEmpty());
  }

  @Test
  void partialSavePreservesOtherBindingChannel() {
    UUID player = UUID.randomUUID();
    assertTrue(this.bindings.save(new PlayerBinding(player, null, "discord-1", 1L)));
    assertTrue(this.bindings.save(new PlayerBinding(player, "10001", null, 2L)));

    PlayerBinding stored = this.bindings.findByPlayer(player).orElseThrow();
    assertEquals("10001", stored.qqId());
    assertEquals("discord-1", stored.discordId());
  }

  private static void assertAllFields(PlayerBinding expected, PlayerBinding actual) {
    assertEquals(expected.playerUuid(), actual.playerUuid());
    assertEquals(expected.qqId(), actual.qqId());
    assertEquals(expected.discordId(), actual.discordId());
    assertEquals(expected.createdAt(), actual.createdAt());
  }
}
