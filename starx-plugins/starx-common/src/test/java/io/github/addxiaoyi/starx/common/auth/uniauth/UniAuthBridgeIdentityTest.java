package io.github.addxiaoyi.starx.common.auth.uniauth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class UniAuthBridgeIdentityTest {

  @Test
  void rejectsAProfileForAnotherUsernameBeforeLocalMigration() {
    UUID connectionUuid = UUID.randomUUID();
    StarxUser account = user(UUID.randomUUID(), "CurrentPlayer");
    UniAuthClient.PlayerProfileResponse profile = new UniAuthClient.PlayerProfileResponse(
        true, true, true, "OtherPlayer", connectionUuid.toString(), null, null, "REGISTERED");

    assertFalse(UniAuthBridge.isProfileIdentityCompatible(
        connectionUuid, "CurrentPlayer", account, profile));
  }

  @Test
  void acceptsThePersistedOfflineUuidAsAnAliasForTheCurrentConnection() {
    UUID connectionUuid = UUID.randomUUID();
    UUID offlineUuid = offlineUuid("CurrentPlayer");
    StarxUser account = user(offlineUuid, "CurrentPlayer");
    UniAuthClient.PlayerProfileResponse profile = new UniAuthClient.PlayerProfileResponse(
        true, true, true, "CurrentPlayer", offlineUuid.toString(), null, null, "REGISTERED");

    assertTrue(UniAuthBridge.isProfileIdentityCompatible(
        connectionUuid, "CurrentPlayer", account, profile));
  }

  @Test
  void doesNotTreatAnUnrelatedUuidAsTheSameAccountJustBecauseTheNameMatches() {
    UUID connectionUuid = UUID.randomUUID();
    StarxUser account = user(UUID.randomUUID(), "CurrentPlayer");

    assertFalse(UniAuthBridge.isOfflineUuidAlias(connectionUuid, "CurrentPlayer", account));
  }

  @Test
  void recognizesOnlyTheDeterministicOfflineUuidAsANameMigrationAlias() {
    UUID offlineUuid = offlineUuid("CurrentPlayer");
    StarxUser account = user(offlineUuid, "CurrentPlayer");

    assertTrue(UniAuthBridge.isOfflineUuidAlias(
        UUID.randomUUID(), "CurrentPlayer", account));
  }

  private static StarxUser user(UUID uuid, String username) {
    return new StarxUser(
        uuid, username, null, null, null, false, Instant.now(), null, null, List.of(), null,
        "uniauth", "pending", null, null, null, null, 0L, null, false);
  }

  private static UUID offlineUuid(String username) {
    return UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
