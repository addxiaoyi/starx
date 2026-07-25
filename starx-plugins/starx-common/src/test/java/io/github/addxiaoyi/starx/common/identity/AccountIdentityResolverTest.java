package io.github.addxiaoyi.starx.common.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.database.DatabaseManager;
import io.github.addxiaoyi.starx.common.config.DatabaseConfig;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
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
}
