package io.github.addxiaoyi.starx.velocity.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.common.platform.ServerRoutingEngine;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BackendRoutingServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

  @Test
  void redirectsNewAdmissionAwayFromDrainingPreferredNode() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    update(registry, "survival-1", 30, 100, 18.0, NOW);
    update(registry, "survival-2", 40, 100, 21.0, NOW);
    registry.markHeartbeatMissed("survival-1");
    registry.markHeartbeatMissed("survival-1");

    BackendRoutingService service = service(registry);
    ServerRoutingEngine.Decision decision = service.select(
        "survival-1", Map.of("survival-1", 5, "survival-2", 1)).orElseThrow();

    assertEquals("survival-2", decision.nodeId());
    assertEquals("draining", decision.rejected().get("survival-1"));
  }

  @Test
  void excludesStaleNodesEvenBeforeTheirNextHealthSweep() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    update(registry, "survival-1", 5, 100, 18.0, NOW.minusSeconds(60));

    assertTrue(service(registry).select("survival-1", Map.of()).isEmpty());
  }

  @Test
  void excludesMaintenanceNodesAndUsesAHealthyShard() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    update(registry, "survival-1", 5, 100, 18.0, true, NOW);
    update(registry, "survival-2", 30, 100, 22.0, false, NOW);

    ServerRoutingEngine.Decision decision = service(registry)
        .select("survival-1", Map.of())
        .orElseThrow();

    assertEquals("survival-2", decision.nodeId());
    assertEquals("maintenance", decision.rejected().get("survival-1"));
  }

  @Test
  void infersOneServerTypeForNumberedShards() {
    assertEquals("factions", BackendRoutingService.inferServerType("factions-02"));
    assertEquals("lobby", BackendRoutingService.inferServerType("lobby"));
  }

  private static BackendRoutingService service(BackendNodeRegistry registry) {
    return new BackendRoutingService(
        registry,
        new ServerRoutingEngine(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofSeconds(45));
  }

  private static void update(
      BackendNodeRegistry registry,
      String name,
      int online,
      int max,
      double mspt,
      Instant seenAt) {
    update(registry, name, online, max, mspt, false, seenAt);
  }

  private static void update(
      BackendNodeRegistry registry,
      String name,
      int online,
      int max,
      double mspt,
      boolean maintenance,
      Instant seenAt) {
    registry.update(name, BridgeMessage.statusResponse(
        name,
        PlatformKind.PAPER,
        "status-" + name,
        Map.of(
            "online", Integer.toString(online),
            "max", Integer.toString(max),
            "serverType", "survival",
            "maintenance", Boolean.toString(maintenance),
            "mspt", Double.toString(mspt),
            "latencyMs", "10")), seenAt);
  }
}
