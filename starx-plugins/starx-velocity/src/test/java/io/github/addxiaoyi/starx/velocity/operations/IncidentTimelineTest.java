package io.github.addxiaoyi.starx.velocity.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.event.StarxEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncidentTimelineTest {
  @Test
  void groupsEventsByCorrelationAndRedactsCredentials() {
    IncidentTimeline timeline = new IncidentTimeline(8, 8);
    UUID correlationId = UUID.randomUUID();
    timeline.append(event("auth:login", correlationId, Map.of(
        "username", "Alex", "password", "never-store", "totpCode", "123456")));
    timeline.append(event("skin:refresh", correlationId, Map.of("provider", "website")));

    IncidentTimeline.Trace trace = timeline.get(correlationId).orElseThrow();
    assertEquals(2, trace.events().size());
    assertEquals("[REDACTED]", trace.events().get(0).payload().get("password"));
    assertEquals("[REDACTED]", trace.events().get(0).payload().get("totpCode"));
    assertEquals("Alex", trace.events().get(0).payload().get("username"));
  }

  @Test
  void capsEventsPerTraceWithoutChangingTheirOrder() {
    IncidentTimeline timeline = new IncidentTimeline(2, 2);
    UUID correlationId = UUID.randomUUID();
    timeline.append(event("one", correlationId, Map.of()));
    timeline.append(event("two", correlationId, Map.of()));
    timeline.append(event("three", correlationId, Map.of()));

    IncidentTimeline.Trace trace = timeline.get(correlationId).orElseThrow();
    assertEquals(2, trace.events().size());
    assertEquals("two", trace.events().get(0).type());
    assertEquals("three", trace.events().get(1).type());
  }

  private static StarxEvent event(String type, UUID correlationId, Map<String, Object> extra) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>(extra);
    payload.put("correlationId", correlationId.toString());
    return new StarxEvent(type, UUID.randomUUID(), Instant.parse("2026-07-22T00:00:00Z"), payload);
  }
}
