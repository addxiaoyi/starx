package io.github.addxiaoyi.starx.common.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServerRoutingEngineTest {
  private final ServerRoutingEngine engine = new ServerRoutingEngine();

  @Test
  void excludesOfflineFullAndDrainingNodes() {
    List<ServerRoutingEngine.Node> nodes = List.of(
        node("offline", false, false, 100, 10, 20, 5, 0, 20),
        node("full", true, false, 20, 20, 20, 5, 0, 20),
        node("draining", true, true, 100, 10, 20, 5, 0, 20),
        node("healthy", true, false, 100, 30, 25, 10, 3, 20));

    ServerRoutingEngine.Decision decision = engine.select(
        new ServerRoutingEngine.Request("survival", null, Set.of()), nodes);

    assertEquals("healthy", decision.nodeId());
    assertEquals(3, decision.rejected().size());
  }

  @Test
  void friendAffinityCannotOverrideAnOverloadedNode() {
    List<ServerRoutingEngine.Node> nodes = List.of(
        node("friends", true, false, 100, 94, 48, 90, 20, 10),
        node("stable", true, false, 100, 35, 22, 12, 2, 20));

    ServerRoutingEngine.Decision decision = engine.select(
        new ServerRoutingEngine.Request("survival", null, Set.of("friends")), nodes);

    assertEquals("stable", decision.nodeId());
  }

  @Test
  void reportsStableQueueEta() {
    ServerRoutingEngine.Decision decision = engine.select(
        new ServerRoutingEngine.Request("survival", "queue", Set.of()),
        List.of(node("queue", true, false, 100, 50, 25, 15, 12, 6)));

    assertEquals(120, decision.etaSeconds());
    assertTrue(decision.factors().containsKey("preference"));
  }

  private static ServerRoutingEngine.Node node(
      String id, boolean online, boolean draining, int capacity, int players,
      double mspt, int latency, int queue, int admissionsPerMinute
  ) {
    return new ServerRoutingEngine.Node(
        id, "survival", online, false, draining, capacity, players, mspt, latency,
        queue, admissionsPerMinute, 100);
  }
}
