package io.github.addxiaoyi.starx.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BackendNodeRegistryTest {

  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

  @Test
  void storesStatusUnderVelocityRegisteredServerName() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    BridgeMessage status = BridgeMessage.statusResponse(
        "declared-lobby",
        PlatformKind.PAPER,
        "request-1",
        Map.of(
            "online", "3",
            "max", "100",
            "capabilities", "server.status,scheduler.main"));

    registry.update("lobby", status, NOW);

    BackendNode node = registry.find("lobby").orElseThrow();
    assertEquals("lobby", node.registeredServer());
    assertEquals("declared-lobby", node.declaredNodeId());
    assertEquals(3, node.onlinePlayers());
    assertEquals(100, node.maxPlayers());
    assertTrue(node.capabilities().contains("scheduler.main"));
    assertTrue(registry.find("declared-lobby").isEmpty());
  }

  @Test
  void helloAfterStatusDoesNotEraseMetrics() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update("lobby", BridgeMessage.statusResponse(
        "lobby", PlatformKind.FOLIA, "request-1", Map.of("online", "5")), NOW);
    registry.update("lobby", new BridgeMessage(
        BridgeProtocol.BACKEND_HELLO,
        "lobby",
        PlatformKind.FOLIA,
        "",
        Map.of("capabilities", "scheduler.region")), NOW.plusSeconds(1));

    BackendNode node = registry.find("lobby").orElseThrow();
    assertEquals(5, node.onlinePlayers());
    assertTrue(node.capabilities().contains("scheduler.region"));
    assertEquals(NOW.plusSeconds(1), node.lastSeen());
  }

  @Test
  void rejectsVelocityAndUnknownMessageTypes() {
    BackendNodeRegistry registry = new BackendNodeRegistry();

    assertThrows(IllegalArgumentException.class, () -> registry.update(
        "lobby", BridgeMessage.hello("proxy", PlatformKind.VELOCITY), NOW));
    assertThrows(IllegalArgumentException.class, () -> registry.update(
        "lobby",
        new BridgeMessage("backend.unknown", "lobby", PlatformKind.PAPER, "", Map.of()),
        NOW));
  }

  @Test
  void marksOldSnapshotsAsStaleWithoutDeletingEvidence() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update("lobby", BridgeMessage.statusResponse(
        "lobby", PlatformKind.PAPER, "request-1", Map.of()), NOW);

    BackendNode node = registry.find("lobby").orElseThrow();
    assertFalse(node.isStale(NOW.plus(Duration.ofMinutes(4)), Duration.ofMinutes(5)));
    assertTrue(node.isStale(NOW.plus(Duration.ofMinutes(6)), Duration.ofMinutes(5)));
    assertEquals(1, registry.all().size());
  }

  @Test
  void tracksConnectedServerNamesWithoutEnumeratingProxyConfiguration() {
    BackendNodeRegistry registry = new BackendNodeRegistry();

    registry.observeServer("lobby");
    registry.observeServer("lobby");
    registry.observeServer("survival");

    assertEquals(List.of("lobby", "survival"), registry.serverNames());
  }

  @Test
  void ignoresOutOfOrderHeartbeatResponses() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update("lobby", BridgeMessage.statusResponse(
        "lobby", PlatformKind.PAPER, "new", Map.of("online", "8")), NOW.plusSeconds(10));
    registry.markHeartbeatMissed("lobby");
    int weightBeforeStaleResponse = registry.admissionWeight("lobby");

    BackendNode node = registry.update("lobby", BridgeMessage.statusResponse(
        "lobby", PlatformKind.PAPER, "old", Map.of("online", "1")), NOW);

    assertEquals(8, node.onlinePlayers());
    assertEquals(NOW.plusSeconds(10), node.lastSeen());
    assertEquals(weightBeforeStaleResponse, registry.admissionWeight("lobby"));
  }
}
