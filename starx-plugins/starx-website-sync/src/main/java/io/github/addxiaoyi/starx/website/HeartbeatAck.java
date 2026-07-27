package io.github.addxiaoyi.starx.website;

import java.time.Instant;
import java.util.Objects;

public record HeartbeatAck(String nodeId, String receivedAt) {
  public HeartbeatAck {
    nodeId = Objects.requireNonNullElse(nodeId, "").trim();
    if (nodeId.isEmpty()) {
      throw new IllegalArgumentException("Heartbeat response did not include a node id");
    }
    receivedAt = Instant.parse(Objects.requireNonNull(receivedAt, "receivedAt")).toString();
  }
}
