package io.github.addxiaoyi.starx.common.platform;

import java.util.Objects;
import java.util.UUID;

public record TraceContext(UUID correlationId, String operation) {
  public TraceContext {
    Objects.requireNonNull(correlationId, "correlationId");
    if (operation == null || operation.isBlank()) {
      throw new IllegalArgumentException("Trace operation is required");
    }
    operation = operation.trim();
  }

  public static TraceContext create() {
    return new TraceContext(UUID.randomUUID(), "root");
  }

  public TraceContext child(String childOperation) {
    return new TraceContext(this.correlationId, childOperation);
  }
}
