package io.github.addxiaoyi.starx.velocity.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import io.github.addxiaoyi.starx.website.NodeSnapshot;
import io.github.addxiaoyi.starx.website.ServerSnapshot;
import io.github.addxiaoyi.starx.website.WebsiteNodeStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VelocityWebsiteSyncTest {
  @Test
  void aggregatesKnownAndUnseenBackendsWithoutFabricatingProxyMetrics() {
    Instant now = Instant.parse("2026-07-27T00:00:00Z");
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update(
        "survival",
        BridgeMessage.statusResponse(
            "survival-1",
            PlatformKind.PAPER,
            "request-1",
            Map.of(
                "online", "8",
                "max", "80",
                "tps", "19.8",
                "mspt", "18.1",
                "minecraft", "1.21.11",
                "maintenance", "false",
                "capabilities", "server.status,skin.refresh")),
        now);

    NodeSnapshot snapshot = VelocityWebsiteSync.buildSnapshot(
        "0.2.0", 12, 100, false, List.of("survival", "lobby"), registry, now);

    assertEquals(12, snapshot.onlinePlayers());
    assertEquals(100, snapshot.maxPlayers());
    assertNull(snapshot.minecraftVersion());
    assertNull(snapshot.tps());
    assertNull(snapshot.mspt());
    assertEquals(List.of("lobby", "survival-1"),
        snapshot.servers().stream().map(ServerSnapshot::nodeId).sorted().toList());
    ServerSnapshot survival = snapshot.servers().stream()
        .filter(server -> server.nodeId().equals("survival-1"))
        .findFirst()
        .orElseThrow();
    assertEquals(WebsiteNodeStatus.ONLINE, survival.status());
    assertEquals(8, survival.onlinePlayers());
    assertEquals(19.8, survival.tps());
    assertEquals("1.21.11", survival.minecraftVersion());
    ServerSnapshot lobby = snapshot.servers().stream()
        .filter(server -> server.nodeId().equals("lobby"))
        .findFirst()
        .orElseThrow();
    assertEquals(WebsiteNodeStatus.OFFLINE, lobby.status());
    assertEquals(0, lobby.onlinePlayers());
    assertNull(lobby.tps());
  }

  @Test
  void marksStaleMaintenanceAndDegradedNodesExplicitly() {
    Instant seen = Instant.parse("2026-07-27T00:00:00Z");
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update(
        "lobby",
        BridgeMessage.statusResponse(
            "lobby-1",
            PlatformKind.FOLIA,
            "request-2",
            Map.of(
                "online", "2",
                "max", "50",
                "maintenance", "true")),
        seen);
    NodeSnapshot maintenance = VelocityWebsiteSync.buildSnapshot(
        "0.2.0", 2, 50, true, List.of("lobby"), registry, seen);
    assertEquals(WebsiteNodeStatus.MAINTENANCE, maintenance.servers().getFirst().status());

    registry.update(
        "lobby",
        BridgeMessage.statusResponse(
            "lobby-1",
            PlatformKind.FOLIA,
            "request-3",
            Map.of("online", "2", "max", "50", "maintenance", "false")),
        seen.plusSeconds(1));
    registry.markHeartbeatMissed("lobby");
    NodeSnapshot degraded = VelocityWebsiteSync.buildSnapshot(
        "0.2.0", 2, 50, false, List.of("lobby"), registry, seen.plusSeconds(1));
    assertEquals(WebsiteNodeStatus.DEGRADED, degraded.servers().getFirst().status());

    NodeSnapshot offline = VelocityWebsiteSync.buildSnapshot(
        "0.2.0", 0, 50, false, List.of("lobby"), registry, seen.plusSeconds(92));
    assertEquals(WebsiteNodeStatus.OFFLINE, offline.servers().getFirst().status());
    assertEquals(0, offline.servers().getFirst().onlinePlayers());
  }

  @Test
  void refusesPartialSnapshotsWhenVelocityExposesMoreThanProtocolLimit() {
    List<String> names = new ArrayList<>();
    for (int index = 0; index < 129; index++) {
      names.add("server-" + index);
    }
    assertThrows(IllegalStateException.class, () -> VelocityWebsiteSync.buildSnapshot(
        "0.2.0", 0, 100, false, names, new BackendNodeRegistry(), Instant.now()));
  }
}
