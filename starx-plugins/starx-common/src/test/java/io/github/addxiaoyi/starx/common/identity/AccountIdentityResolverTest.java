package io.github.addxiaoyi.starx.common.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.database.DatabaseManager;
import io.github.addxiaoyi.starx.common.config.DatabaseConfig;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountIdentityResolverTest {
  @TempDir Path tempDir;

  @Test
  void lazilyCreatesStableAccountWithoutChangingMinecraftUuid() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("identity.db").toAbsolutePath(), 1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID minecraftId = UUID.fromString("e628a809-cffd-4487-b651-a15cae9b0ab7");
      users.save(UserDto.builder().uuid(minecraftId).username("StarPlayer")
          .premium(true).createdAt(Instant.now()).build());
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      String accountId = resolver.accountId(minecraftId);

      assertEquals(minecraftId, resolver.minecraftUuid(accountId));
      assertEquals(minecraftId, identities.findByAccountId(accountId).orElseThrow().minecraftUuid());
      assertEquals(IdentitySource.MOJANG,
          identities.findByAccountId(accountId).orElseThrow().source());
    }
  }

  @Test
  void refreshesTheCurrentNameWhenAnExistingPremiumIdentityChangesName() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("renamed-identity.db").toAbsolutePath(), 1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID minecraftId = UUID.randomUUID();
      users.save(UserDto.builder().uuid(minecraftId).username("OldName")
          .premium(true).createdAt(Instant.now()).build());
      identities.save(new AccountIdentity(
          "mc:" + minecraftId, minecraftId, IdentitySource.MOJANG, "OldName"));
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      String accountId = resolver.accountId(minecraftId, "NewName", IdentitySource.MOJANG);

      assertEquals("mc:" + minecraftId, accountId);
      assertEquals("NewName", resolver.username(accountId));
    }
  }

  @Test
  void doesNotRenameAnExistingIdentityWithoutTrustedSource() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("untrusted-identity-rename.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID minecraftId = UUID.randomUUID();
      users.save(UserDto.builder().uuid(minecraftId).username("TrustedName")
          .premium(true).createdAt(Instant.now()).build());
      identities.save(new AccountIdentity(
          "mc:" + minecraftId, minecraftId, IdentitySource.MOJANG, "TrustedName"));
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      assertEquals("mc:" + minecraftId, resolver.accountId(minecraftId, "ImpostorName"));
      assertEquals("TrustedName", resolver.username("mc:" + minecraftId));
    }
  }

  @Test
  void migratesAnOfflineAccountToTheVerifiedOnlineUuidIdentity() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("offline-migration.db").toAbsolutePath(), 1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID onlineId = UUID.fromString("5d7d6a4a-cb9b-4a07-a1e7-baf4a5c4b7a3");
      UUID offlineId = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:LegacyName").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineId).username("LegacyName")
          .premium(false).createdAt(Instant.now()).build());
      identities.save(new AccountIdentity(
          "mc:" + offlineId, offlineId, IdentitySource.OFFLINE, "LegacyName"));
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      String accountId = resolver.accountId(onlineId, "LegacyName", IdentitySource.MOJANG);

      assertEquals("mc:" + offlineId, accountId);
      assertEquals(onlineId, resolver.resolveMinecraftUuid(offlineId));
      assertEquals(onlineId, resolver.minecraftUuid(accountId));
      assertEquals("LegacyName", resolver.username(accountId));
      users.updatePremium(offlineId, true);
      assertEquals(offlineId, resolver.resolveUser(onlineId).orElseThrow().uuid());
      assertEquals(Set.of(onlineId, offlineId), resolver.knownMinecraftUuids(onlineId));
    }
  }

  @Test
  void createsAnOfflineIdentityBeforeMigratingAFirstPremiumLogin() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("first-premium-migration.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID onlineId = UUID.fromString("7f8e8c6b-7d4a-4e98-9c5f-5c2bdbf9a112");
      UUID offlineId = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:FirstPremium").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineId).username("FirstPremium")
          .premium(false).createdAt(Instant.now()).build());
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      assertEquals("mc:" + offlineId,
          resolver.accountId(onlineId, "FirstPremium", IdentitySource.MOJANG));
      assertEquals(Set.of(offlineId, onlineId), resolver.knownMinecraftUuids(onlineId));
      assertEquals(onlineId, resolver.minecraftUuid("mc:" + offlineId));
    }
  }

  @Test
  void migratesPremiumLoginWhenUsernameCapitalizationChanged() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("case-variant-premium-migration.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID onlineId = UUID.fromString("8a9f9d7c-8e5b-4fa9-ad60-6d3cef0aa223");
      UUID offlineId = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:CaseSensitive").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineId).username("CaseSensitive")
          .premium(false).createdAt(Instant.now()).build());
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      assertEquals("mc:" + offlineId,
          resolver.accountId(onlineId, "casesensitive", IdentitySource.MOJANG));
      assertEquals(Set.of(offlineId, onlineId), resolver.knownMinecraftUuids(onlineId));
    }
  }

  @Test
  void resolvesMigratedOfflineUserAfterTheOnlineNameChanges() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("migrated-name-change.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID onlineId = UUID.fromString("9b0a8c7d-6e5f-4a3b-9c2d-1e0f8a7b6c55");
      UUID offlineId = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:RenamedPremium").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineId).username("RenamedPremium")
          .premium(false).createdAt(Instant.now()).build());
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      resolver.accountId(onlineId, "RenamedPremium", IdentitySource.MOJANG);
      resolver.accountId(onlineId, "CurrentPremiumName", IdentitySource.MOJANG);

      assertEquals(offlineId, resolver.resolveUser(onlineId).orElseThrow().uuid());
    }
  }

  @Test
  void resolvesFullUserByTheCurrentOnlineNameAfterMigration() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("migrated-full-name.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID onlineId = UUID.fromString("0a1b2c3d-4e5f-4678-9a0b-1c2d3e4f5a66");
      UUID offlineId = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:FullNameLegacy").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineId).username("FullNameLegacy")
          .premium(false).createdAt(Instant.now()).build());
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      resolver.accountId(onlineId, "FullNameLegacy", IdentitySource.MOJANG);
      resolver.accountId(onlineId, "FullNameCurrent", IdentitySource.MOJANG);

      Optional<StarxUser> user = resolver.resolveFullUserByName("FullNameCurrent");

      assertEquals(offlineId, user.orElseThrow().uuid());
      assertEquals("FullNameLegacy", user.orElseThrow().username());
      assertEquals(offlineId, resolver.resolveUserByName("FullNameCurrent").orElseThrow().uuid());
    }
  }

  @Test
  void retainsAllUuidAliasesAcrossRepeatedOnlineIdentityChanges() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("repeated-offline-migration.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID firstOnlineId = UUID.fromString("5d7d6a4a-cb9b-4a07-a1e7-baf4a5c4b7a3");
      UUID secondOnlineId = UUID.fromString("6e8e7b5b-dcaa-4b18-b2f8-cb05b6d5c8b4");
      UUID offlineId = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:LegacyName").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineId).username("LegacyName")
          .premium(false).createdAt(Instant.now()).build());
      identities.save(new AccountIdentity(
          "mc:" + offlineId, offlineId, IdentitySource.OFFLINE, "LegacyName"));
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      assertEquals("mc:" + offlineId,
          resolver.accountId(firstOnlineId, "LegacyName", IdentitySource.MOJANG));
      assertEquals("mc:" + offlineId,
          resolver.accountId(secondOnlineId, "LegacyName", IdentitySource.MOJANG));

      assertEquals(secondOnlineId, resolver.resolveMinecraftUuid(offlineId));
      assertEquals(secondOnlineId, resolver.resolveMinecraftUuid(firstOnlineId));
      assertEquals(Set.of(offlineId, firstOnlineId, secondOnlineId),
          resolver.knownMinecraftUuids(offlineId));
      assertEquals(secondOnlineId, resolver.minecraftUuid("mc:" + offlineId));
    }
  }

  @Test
  void keepsTrustedCanonicalUuidWhenAnOfflineAliasIsRenamed() throws Exception {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("offline-alias-rename.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID onlineId = UUID.fromString("7e8d9c0b-1a2f-4b3c-8d5e-6f7a8b9c0d12");
      UUID offlineId = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:AliasRename").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineId).username("AliasRename")
          .premium(false).createdAt(Instant.now()).build());
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      resolver.accountId(onlineId, "AliasRename", IdentitySource.MOJANG);
      try (Connection connection = database.getDataSource().getConnection();
           PreparedStatement update = connection.prepareStatement(
               "UPDATE starx_account_identities SET last_seen_at = 1 WHERE minecraft_uuid = ?")) {
        update.setString(1, onlineId.toString());
        update.executeUpdate();
      }
      resolver.accountId(offlineId, "RenamedAlias");

      assertEquals(onlineId, resolver.resolveMinecraftUuid(offlineId));
      assertEquals(onlineId, resolver.minecraftUuid("mc:" + offlineId));
    }
  }

  @Test
  void doesNotAdoptAnUnrelatedOfflineAccountWithoutPlayerName() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("offline-identity-isolation.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID unrelatedOnlineId = UUID.fromString("1f3fdc88-2b1d-4edc-9295-2a3cf2a5ee9c");
      UUID offlineId = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:LegacyName").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineId).username("LegacyName")
          .premium(false).createdAt(Instant.now()).build());
      identities.save(new AccountIdentity(
          "mc:" + offlineId, offlineId, IdentitySource.OFFLINE, "LegacyName"));
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      assertThrows(IllegalArgumentException.class, () -> resolver.accountId(unrelatedOnlineId));
      assertEquals(offlineId, resolver.minecraftUuid("mc:" + offlineId));
    }
  }

  @Test
  void doesNotMigrateAnOfflineAccountForAnUntrustedUuidEvenWhenNameMatches() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("untrusted-offline-migration.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID unrelatedUuid = UUID.fromString("2a4e1b0a-3c6d-4f18-9e72-6b5d9c0a1f33");
      UUID offlineId = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:UntrustedLegacy").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineId).username("UntrustedLegacy")
          .premium(false).createdAt(Instant.now()).build());
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      assertThrows(IllegalArgumentException.class,
          () -> resolver.accountId(unrelatedUuid, "UntrustedLegacy"));
      assertEquals(null, resolver.minecraftUuid("mc:" + offlineId));
    }
  }

  @Test
  void databaseRejectsCaseVariantUsernames() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("username-uniqueness.db").toAbsolutePath(), 1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      users.save(UserDto.builder().uuid(UUID.randomUUID()).username("Alex")
          .createdAt(Instant.now()).build());

      assertThrows(RuntimeException.class, () -> users.save(
          UserDto.builder().uuid(UUID.randomUUID()).username("alex")
              .createdAt(Instant.now()).build()));
    }
  }

  @Test
  void preservesTheTrustedFloodgateSourceWhenCreatingAnIdentity() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("floodgate-identity-source.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID floodgateUuid = UUID.fromString("3b5f2c1b-4d7e-4a29-8f63-7c6e0b1d2a44");
      users.save(UserDto.builder().uuid(floodgateUuid).username("BedrockPlayer")
          .premium(false).createdAt(Instant.now()).build());
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      String accountId = resolver.accountId(
          floodgateUuid, "BedrockPlayer", IdentitySource.FLOODGATE);

      assertEquals(IdentitySource.FLOODGATE,
          identities.findByAccountId(accountId).orElseThrow().source());
    }
  }

  @Test
  void resolvesTheOfflineAccountThroughATrustedFloodgateIdentity() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("floodgate-account-resolution.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID floodgateUuid = UUID.fromString("4c6e3d2c-5e8f-4b3a-9f74-8d7e1c2b3a55");
      UUID offlineUuid = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:BedrockLegacy").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineUuid).username("BedrockLegacy")
          .premium(false).createdAt(Instant.now()).build());
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      resolver.accountId(floodgateUuid, "BedrockLegacy", IdentitySource.FLOODGATE);

      assertEquals(floodgateUuid, resolver.resolveMinecraftUuid(offlineUuid));
      assertEquals(offlineUuid, resolver.resolveUser(floodgateUuid).orElseThrow().uuid());
    }
  }

  @Test
  void resolvesAStoredFloodgateMigrationAliasToItsCurrentUuid() {
    DatabaseConfig config = new DatabaseConfig(
        "sqlite", "", 0, "", "", "",
        "jdbc:sqlite:" + tempDir.resolve("floodgate-migration-alias.db").toAbsolutePath(),
        1, 10_000L);
    try (DatabaseManager database = new DatabaseManager(config)) {
      JdbcUserRepository users = new JdbcUserRepository(database.getDataSource());
      JdbcAccountIdentityRepository identities =
          new JdbcAccountIdentityRepository(database.getDataSource());
      UUID floodgateUuid = UUID.fromString("5d7d6a4a-cb9b-4a07-a1e7-baf4a5c4b7a3");
      UUID offlineUuid = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:LegacyBedrock").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      users.save(UserDto.builder().uuid(offlineUuid).username("LegacyBedrock")
          .premium(false).createdAt(Instant.now()).build());
      identities.save(new AccountIdentity(
          "mc:" + offlineUuid, floodgateUuid, IdentitySource.FLOODGATE, "LegacyBedrock"));
      AccountIdentityResolver resolver = new AccountIdentityResolver(identities, users);

      assertEquals(floodgateUuid, resolver.resolveMinecraftUuid(offlineUuid));
    }
  }
}
