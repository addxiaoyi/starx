package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BackendCapacityResolverTest {

  private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

  @Test
  void usesRecentBridgeCapacityForRegisteredServer() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update("factions", BridgeMessage.statusResponse(
        "factions-node", PlatformKind.PAPER, "request-1", Map.of("max", "50")), NOW);

    assertEquals(50, BackendCapacityResolver.resolve(registry, "factions", NOW, 80));
  }

  @Test
  void returnsZeroWhenBridgeCapacityIsUnavailableOrStale() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update("factions", BridgeMessage.statusResponse(
        "factions-node", PlatformKind.PAPER, "request-1", Map.of("max", "invalid")), NOW);

    assertEquals(80, BackendCapacityResolver.resolve(registry, "factions", NOW, 80));
    assertEquals(80, BackendCapacityResolver.resolve(registry, "unknown", NOW, 80));
    assertEquals(80, BackendCapacityResolver.resolve(
        registry, "factions", NOW.plusSeconds(301), 80));
    assertEquals(0, BackendCapacityResolver.resolve(registry, "unknown", NOW, -1));
  }

  @Test
  void drainingNodeDoesNotReceiveNewCapacityAdmissions() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update("factions", BridgeMessage.statusResponse(
        "factions-node", PlatformKind.PAPER, "request-1", Map.of("max", "50")), NOW);
    registry.markHeartbeatMissed("factions");
    registry.markHeartbeatMissed("factions");

    assertEquals(0, BackendCapacityResolver.resolve(registry, "factions", NOW, 80));
  }
}
