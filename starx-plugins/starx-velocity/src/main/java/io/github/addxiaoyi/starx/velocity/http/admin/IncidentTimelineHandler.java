package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.operations.IncidentTimeline;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class IncidentTimelineHandler implements AdminHandler {
  private final IncidentTimeline timeline;

  public IncidentTimelineHandler(IncidentTimeline timeline) {
    this.timeline = Objects.requireNonNull(timeline, "timeline");
  }

  @Override
  public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... authFilter) {
    routes.get("/v1/admin/incidents", this.chain(this::handle, authFilter));
    routes.get("/v1/security/events", this.chain(this::handleSecurityEvents, authFilter));
  }

  private RouteRegistrar.RouteHandler chain(
      RouteRegistrar.RouteHandler handler,
      RouteRegistrar.RouteHandler... authFilter
  ) {
    return ctx -> {
      for (RouteRegistrar.RouteHandler filter : authFilter) filter.handle(ctx);
      handler.handle(ctx);
    };
  }

  private void handle(JsonHttpExchange ctx) throws IOException {
    ctx.status(200).json(snapshot(this.timeline, ctx.queryParam("correlationId")));
  }

  private void handleSecurityEvents(JsonHttpExchange ctx) throws IOException {
    String username = ctx.queryParam("name");
    int limit;
    try {
      limit = Math.min(100, Math.max(1, Integer.parseInt(
          Objects.requireNonNullElse(ctx.queryParam("limit"), "20"))));
    } catch (NumberFormatException error) {
      limit = 20;
    }
    if (username == null || username.isBlank() || username.length() > 16) {
      ctx.status(400).json(Map.of("ok", false, "error", "valid name is required"));
      return;
    }
    ctx.status(200).json(securityEvents(this.timeline, username, limit));
  }

  static Map<String, Object> securityEvents(
      IncidentTimeline timeline, String username, int limit) {
    Objects.requireNonNull(timeline, "timeline");
    String target = Objects.requireNonNull(username, "username").trim();
    int boundedLimit = Math.min(100, Math.max(1, limit));
    List<Map<String, Object>> all = timeline.snapshot().stream()
        .flatMap(trace -> trace.events().stream().map(event -> Map.<String, Object>of(
            "id", event.eventId().toString(),
            "correlationId", trace.correlationId().toString(),
            "type", event.type(),
            "timestamp", event.timestamp().toString(),
            "payload", event.payload())))
        .filter(event -> {
          Object payload = event.get("payload");
          if (!(payload instanceof Map<?, ?> values)) return false;
          Object candidate = values.get("username");
          return candidate != null && target.equalsIgnoreCase(String.valueOf(candidate));
        })
        .sorted((left, right) -> String.valueOf(right.get("timestamp"))
            .compareTo(String.valueOf(left.get("timestamp"))))
        .toList();
    return Map.of(
        "ok", true,
        "total", all.size(),
        "items", all.stream().limit(boundedLimit).toList());
  }

  static Map<String, Object> snapshot(IncidentTimeline timeline, String rawCorrelationId) {
    Objects.requireNonNull(timeline, "timeline");
    List<IncidentTimeline.Trace> traces;
    if (rawCorrelationId == null || rawCorrelationId.isBlank()) {
      traces = timeline.snapshot();
    } else {
      try {
        traces = timeline.get(UUID.fromString(rawCorrelationId.trim())).stream().toList();
      } catch (IllegalArgumentException error) {
        traces = List.of();
      }
    }
    return Map.of("ok", true, "count", traces.size(), "data", traces);
  }
}
