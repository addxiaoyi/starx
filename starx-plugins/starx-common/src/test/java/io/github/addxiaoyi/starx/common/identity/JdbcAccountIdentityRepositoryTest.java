package io.github.addxiaoyi.starx.common.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcAccountIdentityRepositoryTest {
  @TempDir Path tempDir;
  private SQLiteDataSource source;
  private JdbcAccountIdentityRepository identities;

  @BeforeEach
  void setUp() throws Exception {
    source = new SQLiteDataSource();
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

  @Test
  void rebindKeepsEveryHistoricalUuidAndSelectsTheLatestIdentity() {
    UUID offlineUuid = UUID.randomUUID();
    UUID firstOnlineUuid = UUID.randomUUID();
    UUID secondOnlineUuid = UUID.randomUUID();
    identities.save(new AccountIdentity("account-a", offlineUuid, IdentitySource.OFFLINE, "Player"));

    identities.rebindMinecraftUuid(offlineUuid, firstOnlineUuid, IdentitySource.MOJANG, "Player");
    identities.rebindMinecraftUuid(firstOnlineUuid, secondOnlineUuid, IdentitySource.MOJANG, "Player");

    List<AccountIdentity> history = identities.findAllByAccountId("account-a");
    assertEquals(3, history.size());
    assertEquals(secondOnlineUuid, identities.findByAccountId("account-a").orElseThrow().minecraftUuid());
    assertEquals("account-a", identities.findByMinecraftUuid(offlineUuid).orElseThrow().accountId());
    assertEquals("account-a", identities.findByMinecraftUuid(firstOnlineUuid).orElseThrow().accountId());
  }

  @Test
  void accountCreationFailureDoesNotContinueWithAnOrphanIdentity() throws Exception {
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TRIGGER fail_account_insert BEFORE INSERT ON starx_accounts "
          + "BEGIN SELECT RAISE(ABORT, 'simulated account write failure'); END");
    }

    assertThrows(IllegalStateException.class, () -> identities.save(
        new AccountIdentity("account-failure", UUID.randomUUID(), IdentitySource.OFFLINE, "Player")));
  }

  @Test
  void existingAccountDoesNotHideARealAccountWriteFailure() throws Exception {
    UUID playerId = UUID.randomUUID();
    identities.save(new AccountIdentity("account-a", UUID.randomUUID(), IdentitySource.OFFLINE, "Player"));
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TRIGGER fail_account_insert BEFORE INSERT ON starx_accounts "
          + "BEGIN SELECT RAISE(ABORT, 'simulated account write failure'); END");
    }

    assertThrows(IllegalStateException.class, () -> identities.save(
        new AccountIdentity("account-a", playerId, IdentitySource.MOJANG, "Player")));
    assertTrue(identities.findByMinecraftUuid(playerId).isEmpty());
  }

}
