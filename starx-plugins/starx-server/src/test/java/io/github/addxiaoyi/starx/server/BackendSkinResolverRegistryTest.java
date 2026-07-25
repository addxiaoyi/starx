package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BackendSkinResolverRegistryTest {

  @Test
  void aLateProviderReplacesTheUnavailableResolverForExistingSessions() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    BackendSkinResolverRegistry registry = new BackendSkinResolverRegistry();
    BackendBridgeSession session = new BackendBridgeSession(
        "lobby",
        ServerPlatform.PAPER,
        Map::of,
        registry,
        Clock.systemUTC());

    BridgeMessage before = request(session, uuid, "Alex", "before");
    registry.replace((requestedUuid, name) -> Optional.of(new BackendSkinProfile(
        requestedUuid,
        name,
        "skinsrestorer",
        "texture-value",
        "texture-signature")));
    BridgeMessage after = request(session, uuid, "Alex", "after");
    registry.clear();
    BridgeMessage cleared = request(session, uuid, "Alex", "cleared");

    assertEquals("false", before.attributes().get("found"));
    assertEquals("true", after.attributes().get("found"));
    assertEquals("skinsrestorer", after.attributes().get("provider"));
    assertEquals("false", cleared.attributes().get("found"));
  }

  private static BridgeMessage request(
      BackendBridgeSession session,
      UUID uuid,
      String name,
      String correlationId
  ) {
    return session.receive(BridgeMessage.skinRequest(
        "proxy", correlationId, uuid.toString(), name)).orElseThrow();
  }
}
