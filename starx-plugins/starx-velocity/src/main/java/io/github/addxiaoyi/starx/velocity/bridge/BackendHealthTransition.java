package io.github.addxiaoyi.starx.velocity.bridge;

import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.common.platform.NodeHealthStateMachine;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class BackendHealthTransition {
  static final String EVENT_TYPE = "backend:health:changed";

  private BackendHealthTransition() {
  }

  static Optional<StarxEvent> event(
      String serverName,
      NodeHealthStateMachine.Snapshot previous,
      NodeHealthStateMachine.Snapshot current,
      BackendNode node,
      Instant occurredAt) {
    Objects.requireNonNull(serverName, "serverName");
    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (previous.state() == current.state()) {
      return Optional.empty();
    }

    UUID correlationId = UUID.nameUUIDFromBytes(
        ("starx:backend-health:" + serverName).getBytes(StandardCharsets.UTF_8));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("correlationId", correlationId.toString());
    payload.put("server", serverName);
    payload.put("previousState", previous.state().name());
    payload.put("state", current.state().name());
    payload.put("admissionWeight", current.admissionWeight());
    payload.put("missedHeartbeats", current.missedHeartbeats());
    payload.put("bridgeSeen", node != null);
    payload.put("nodeId", node == null ? "unknown" : node.declaredNodeId());
    payload.put("platform", node == null ? "unknown" : node.platform().name());
    payload.put("lastSeen", node == null ? "unknown" : node.lastSeen().toString());
    payload.put("capabilities", node == null
        ? ""
        : String.join(",", new java.util.TreeSet<>(node.capabilities())));
    return Optional.of(new StarxEvent(
        EVENT_TYPE, UUID.randomUUID(), occurredAt, payload));
  }
}
