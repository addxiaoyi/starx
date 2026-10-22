package io.github.addxiaoyi.starx.velocity.website;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

final class WebsiteHealthEventPublisher {
  static final String EVENT_TYPE = "website:health:changed";

  private final EventBus eventBus;
  private final String nodeId;
  private final Consumer<String> errorLogger;
  private final Map<String, UUID> activeIncidents = new java.util.HashMap<>();

  WebsiteHealthEventPublisher(
      EventBus eventBus,
      String nodeId,
      Consumer<String> errorLogger) {
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.errorLogger = Objects.requireNonNull(errorLogger, "errorLogger");
  }

  synchronized void publish(
      String component,
      String previousState,
      String state,
      String errorCode,
      Map<String, ?> metrics) {
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(previousState, "previousState");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(metrics, "metrics");
    if (previousState.equals(state)) {
      return;
    }

    boolean unhealthy = unhealthy(component, state);
    UUID correlationId = this.activeIncidents.get(component);
    if (!unhealthy && correlationId == null) {
      return;
    }
    boolean recovered = recovered(component, state);
    if (!unhealthy && !recovered) {
      return;
    }
    if (unhealthy && correlationId == null) {
      correlationId = UUID.randomUUID();
      this.activeIncidents.put(component, correlationId);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("correlationId", correlationId.toString());
    payload.put("nodeId", this.nodeId);
    payload.put("component", component);
    payload.put("previousState", previousState);
    payload.put("state", state);
    payload.put("severity", recovered ? "RECOVERED" : "WARNING");
    if (errorCode != null && !errorCode.isBlank()) {
      payload.put("errorCode", errorCode);
    }
    payload.put("metrics", Map.copyOf(metrics));
    try {
      this.eventBus.publish(new StarxEvent(EVENT_TYPE, UUID.randomUUID(), Instant.now(), payload));
    } catch (RuntimeException error) {
      this.errorLogger.accept(
          "Unable to publish website health transition: component=" + component
              + " error=" + error.getClass().getSimpleName());
    }
    if (recovered) {
      this.activeIncidents.remove(component);
    }
  }

  private static boolean unhealthy(String component, String state) {
    return switch (component) {
      case "runtime" -> "BACKOFF".equals(state) || "AUTH_FAILED".equals(state);
      case "circuit-breaker" -> "OPEN".equals(state) || "HALF_OPEN".equals(state);
      case "worker-queue", "http-transport" -> "SATURATED".equals(state);
      default -> false;
    };
  }

  private static boolean recovered(String component, String state) {
    return switch (component) {
      case "runtime" -> "ACTIVE".equals(state);
      case "circuit-breaker" -> "CLOSED".equals(state);
      case "worker-queue", "http-transport" -> "NORMAL".equals(state);
      default -> false;
    };
  }
}
