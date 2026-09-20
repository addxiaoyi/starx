package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.addxiaoyi.starx.velocity.status.NetworkStatusSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class NetworkStatusPayloadTest {

  @Test
  void exposesOnlyAggregateNetworkState() {
    NetworkStatusSnapshot snapshot = NetworkStatusSnapshot.of(
        Instant.parse("2026-07-17T04:00:00Z"),
        5,
        100,
        List.of(new NetworkStatusSnapshot.ServerStatus(
            "factions", 3, 50, Map.of(
                "skinProvider", "skinsrestorer",
                "skinBridge", "available"))));

    Map<String, Object> payload = NetworkStatusPayload.from(
        snapshot,
        Map.of(
            "sampleCount", 42,
            "lastCollectedAt", "2026-07-17T03:59:00Z",
            "maintenance", true,
            "queue", Map.of("servers", Map.of("factions", Map.of(
                "queued", 3, "tailEtaSeconds", 9)))));

    assertEquals(5, payload.get("onlinePlayers"));
    assertEquals(100, payload.get("maxPlayers"));
    assertEquals("2026-07-17T04:00:00Z", payload.get("collectedAt"));
    assertFalse(payload.containsKey("players"));
    assertEquals(1, ((List<?>) payload.get("servers")).size());
    assertEquals(42, ((Map<?, ?>) payload.get("metrics")).get("sampleCount"));
    assertEquals(true, ((Map<?, ?>) payload.get("metrics")).get("maintenance"));
    Map<?, ?> queue = (Map<?, ?>) ((Map<?, ?>) payload.get("metrics")).get("queue");
    assertEquals(3, ((Map<?, ?>) ((Map<?, ?>) queue.get("servers")).get("factions")).get("queued"));
    Map<?, ?> server = (Map<?, ?>) ((List<?>) payload.get("servers")).get(0);
    assertEquals("skinsrestorer", server.get("skinProvider"));
    assertEquals("available", server.get("skinBridge"));
  }
}
