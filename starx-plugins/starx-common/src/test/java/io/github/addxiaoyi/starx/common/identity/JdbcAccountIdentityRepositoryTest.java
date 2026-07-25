package io.github.addxiaoyi.starx.common.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcAccountIdentityRepositoryTest {
  @TempDir Path tempDir;
  private JdbcAccountIdentityRepository identities;

  @BeforeEach
  void setUp() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("identity.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcAccountIdentityRepository.CREATE_ACCOUNTS_SQL);
      sql.execute(JdbcAccountIdentityRepository.CREATE_IDENTITIES_SQL);
    }
    identities = new JdbcAccountIdentityRepository(source);
  }

  @Test
  void aMinecraftUuidCanBelongToOnlyOneAccount() {
    UUID playerId = UUID.randomUUID();
    identities.save(new AccountIdentity("account-a", playerId, IdentitySource.MOJANG, "Player"));

    assertThrows(IdentityConflictException.class, () ->
        identities.save(new AccountIdentity("account-b", playerId, IdentitySource.MOJANG, "Player")));
  }

  @Test
  void renameUpdatesOnlyTheName() {
    UUID playerId = UUID.randomUUID();
    identities.save(new AccountIdentity("account-a", playerId, IdentitySource.OFFLINE, "OldName"));

    identities.rename(playerId, "NewName");

    AccountIdentity renamed = identities.findByMinecraftUuid(playerId).orElseThrow();
    assertEquals(renamed, identities.findByAccountId(renamed.accountId()).orElseThrow());
    assertEquals(playerId, renamed.minecraftUuid());
    assertEquals("NewName", renamed.currentName());
    assertEquals(IdentitySource.OFFLINE, renamed.source());
  }
}
