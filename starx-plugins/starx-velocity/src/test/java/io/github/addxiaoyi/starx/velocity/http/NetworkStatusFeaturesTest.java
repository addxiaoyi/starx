package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNode;
import io.github.addxiaoyi.starx.common.platform.NodeHealthStateMachine;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class NetworkStatusFeaturesTest {

  @Test
  void exposesOperationalBackendMetadataWithoutPlayerIdentities() {
    Instant seenAt = Instant.parse("2026-07-18T06:00:00Z");
    BackendNode node = new BackendNode(
        "factions",
        "factions",
        PlatformKind.PAPER,
        Set.of("bridge.v1", "scheduler.main"),
        Map.ofEntries(
            Map.entry("skinProvider", "none"),
            Map.entry("skinBridge", "unavailable"),
            Map.entry("transport", "heartbeat-http"),
            Map.entry("execution", "main-thread"),
            Map.entry("minecraft", "1.21.11"),
            Map.entry("implementation", "Paper"),
            Map.entry("uptimeMillis", "90000"),
            Map.entry("httpCommandsAccepted", "4"),
            Map.entry("httpCommandsDelivered", "3"),
            Map.entry("httpCommandsRejected", "1"),
            Map.entry("httpCommandsQueued", "1")),
        seenAt);

    Map<String, String> features = NetworkStatusHandler.featuresOf(
        node, seenAt.plusSeconds(10));

    assertEquals("PAPER", features.get("platform"));
    assertEquals("bridge.v1,scheduler.main", features.get("capabilities"));
    assertEquals("main-thread", features.get("execution"));
    assertEquals("1.21.11", features.get("minecraft"));
    assertEquals("Paper", features.get("implementation"));
    assertEquals("90000", features.get("uptimeMillis"));
    assertEquals(seenAt.toString(), features.get("lastSeen"));
    assertEquals("heartbeat-http", features.get("transport"));
    assertEquals("4", features.get("httpCommandsAccepted"));
    assertEquals("3", features.get("httpCommandsDelivered"));
    assertEquals("1", features.get("httpCommandsRejected"));
    assertEquals("1", features.get("httpCommandsQueued"));
  }

  @Test
  void exposesAdmissionHealthForDigitalTwinConsumers() {
    Instant seenAt = Instant.parse("2026-07-18T06:00:00Z");
    BackendNode node = new BackendNode(
        "factions", "factions", PlatformKind.PAPER, Set.of(), Map.of(), seenAt);
    NodeHealthStateMachine.Snapshot draining = new NodeHealthStateMachine.Snapshot(
        NodeHealthStateMachine.State.DRAINING, 0, 2, 0);

    Map<String, String> features = NetworkStatusHandler.featuresOf(
        node, seenAt.plusSeconds(10), draining);

    assertEquals("DRAINING", features.get("healthState"));
    assertEquals("0", features.get("admissionWeight"));
  }
}
