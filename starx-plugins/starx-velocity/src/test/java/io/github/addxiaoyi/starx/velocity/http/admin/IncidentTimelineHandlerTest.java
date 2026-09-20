package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.operations.IncidentTimeline;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncidentTimelineHandlerTest {
  @Test
  void filtersSnapshotByCorrelationId() {
    IncidentTimeline timeline = new IncidentTimeline(8, 8);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    timeline.append(new StarxEvent("auth:login", Map.of("correlationId", first.toString())));
    timeline.append(new StarxEvent("queue:joined", Map.of("correlationId", second.toString())));

    Map<String, Object> response = IncidentTimelineHandler.snapshot(timeline, first.toString());

    assertEquals(true, response.get("ok"));
    assertEquals(1, response.get("count"));
  }

  @Test
  void filtersSecurityEventsByUsernameAndLimit() {
    IncidentTimeline timeline = new IncidentTimeline(8, 8);
    timeline.append(new StarxEvent("security:risk:high", Map.of("username", "Alex", "score", 90)));
    timeline.append(new StarxEvent("security:bot:detected", Map.of("username", "Steve")));
    timeline.append(new StarxEvent("security:anticheat:detection", Map.of("username", "alex")));

    Map<String, Object> response = IncidentTimelineHandler.securityEvents(timeline, "Alex", 1);

    assertEquals(true, response.get("ok"));
    assertEquals(2, response.get("total"));
    assertEquals(1, ((java.util.List<?>) response.get("items")).size());
  }

  @Test
  void invalidCorrelationIdReturnsAnEmptyResult() {
    Map<String, Object> response = IncidentTimelineHandler.snapshot(
        new IncidentTimeline(8, 8), "not-a-uuid");

    assertEquals(0, response.get("count"));
  }
}
