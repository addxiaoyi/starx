package io.github.addxiaoyi.starx.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BackendNodeRegistryHealthTest {
  @Test
  void aNodeDrainsBeforeItIsEligibleAgainAfterRecovery() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update("lobby", BridgeMessage.statusResponse(
        "node-1", PlatformKind.PAPER, "h1", Map.of("online", "0", "max", "20")),
        Instant.parse("2026-07-22T00:00:00Z"));

    assertEquals(100, registry.admissionWeight("lobby"));
    assertEquals(50, registry.markHeartbeatMissed("lobby").admissionWeight());
    assertEquals(0, registry.markHeartbeatMissed("lobby").admissionWeight());
    assertEquals(0, registry.admissionWeight("lobby"));
    assertEquals(10, registry.markHeartbeatHealthy("lobby").admissionWeight());
    assertEquals(25, registry.markHeartbeatHealthy("lobby").admissionWeight());
    assertEquals(50, registry.markHeartbeatHealthy("lobby").admissionWeight());
    assertEquals(100, registry.markHeartbeatHealthy("lobby").admissionWeight());
  }
}
