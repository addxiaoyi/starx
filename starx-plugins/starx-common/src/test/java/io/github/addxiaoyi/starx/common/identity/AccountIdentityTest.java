package io.github.addxiaoyi.starx.common.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountIdentityTest {
  @Test
  void renamePreservesTheExactMinecraftUuidAndSource() {
    UUID minecraftId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    AccountIdentity identity = new AccountIdentity(
        "account-1", minecraftId, IdentitySource.MOJANG, "OldName");

    AccountIdentity renamed = identity.rename("NewName");

    assertSame(minecraftId, renamed.minecraftUuid());
    assertEquals(IdentitySource.MOJANG, renamed.source());
    assertEquals("NewName", renamed.currentName());
  }

  @Test
  void refusesToReplaceAnExistingMinecraftUuid() {
    AccountIdentity identity = new AccountIdentity(
        "account-1", UUID.randomUUID(), IdentitySource.FLOODGATE, "BedrockUser");

    assertThrows(UnsupportedOperationException.class,
        () -> identity.replaceMinecraftUuid(UUID.randomUUID()));
  }
}
