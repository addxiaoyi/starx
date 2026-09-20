package io.github.addxiaoyi.starx.velocity.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class FloodgateIdentityProviderTest {

  @Test
  void delegatesTrustedIdentityChecksToAnInstalledFloodgateApi() {
    UUID bedrockPlayer = UUID.randomUUID();
    FakeFloodgateApi api = new FakeFloodgateApi();
    api.players.add(bedrockPlayer);
    FloodgateIdentityProvider provider = FloodgateIdentityProvider.fromApi(api);

    assertTrue(provider.isTrusted(bedrockPlayer));
    assertFalse(provider.isTrusted(UUID.randomUUID()));
  }

  @Test
  void rejectsAnIncompatibleFloodgateApiInsteadOfSilentlyDisablingIt() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FloodgateIdentityProvider.fromApi(new Object()));
  }

  public static final class FakeFloodgateApi {
    private final Set<UUID> players = new HashSet<>();

    public boolean isFloodgatePlayer(UUID playerId) {
      return this.players.contains(playerId);
    }
  }
}
