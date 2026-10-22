package io.github.addxiaoyi.starx.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.common.platform.NodeHealthStateMachine;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BackendHealthTransitionTest {

  private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

  @Test
  void ignoresRepeatedStateSoHeartbeatPollingDoesNotRepeatAlerts() {
    NodeHealthStateMachine.Snapshot offline = snapshot(
        NodeHealthStateMachine.State.OFFLINE, 0, 3);

    assertTrue(BackendHealthTransition.event("survival", offline, offline, null, NOW).isEmpty());
  }

  @Test
  void createsAStablePerBackendIncidentWhenHealthChanges() {
    NodeHealthStateMachine.Snapshot healthy = snapshot(
        NodeHealthStateMachine.State.HEALTHY, 100, 0);
    NodeHealthStateMachine.Snapshot suspect = snapshot(
        NodeHealthStateMachine.State.SUSPECT, 50, 1);
    BackendNode node = new BackendNode(
        "survival", "survival-node", PlatformKind.PAPER,
        java.util.Set.of("status", "skin"), Map.of(), NOW);

    var failed = BackendHealthTransition.event("survival", healthy, suspect, node, NOW).orElseThrow();
    var recovered = BackendHealthTransition.event("survival", suspect, healthy, node, NOW).orElseThrow();

    assertEquals(BackendHealthTransition.EVENT_TYPE, failed.type());
    assertEquals("HEALTHY", failed.payload().get("previousState"));
    assertEquals("SUSPECT", failed.payload().get("state"));
    assertEquals(50, failed.payload().get("admissionWeight"));
    assertEquals("survival-node", failed.payload().get("nodeId"));
    assertTrue(Boolean.TRUE.equals(failed.payload().get("bridgeSeen")));
    assertEquals(failed.payload().get("correlationId"), recovered.payload().get("correlationId"));
    assertNotEquals(failed.eventId(), recovered.eventId());
  }

  @Test
  void warmingHeartbeatsDoNotRepeatUntilTheBackendActuallyRecovers() {
    NodeHealthStateMachine.Snapshot warming = snapshot(
        NodeHealthStateMachine.State.WARMING, 25, 0);
    NodeHealthStateMachine.Snapshot healthy = snapshot(
        NodeHealthStateMachine.State.HEALTHY, 100, 0);

    assertTrue(BackendHealthTransition.event("survival", warming, warming, null, NOW).isEmpty());
    var recovered = BackendHealthTransition.event(
        "survival", warming, healthy, null, NOW).orElseThrow();

    assertEquals("WARMING", recovered.payload().get("previousState"));
    assertEquals("HEALTHY", recovered.payload().get("state"));
  }

  private static NodeHealthStateMachine.Snapshot snapshot(
      NodeHealthStateMachine.State state,
      int admissionWeight,
      int missedHeartbeats) {
    return new NodeHealthStateMachine.Snapshot(state, admissionWeight, missedHeartbeats, 0);
  }
}
