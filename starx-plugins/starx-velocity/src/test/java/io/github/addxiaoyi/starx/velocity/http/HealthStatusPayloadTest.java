package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HealthStatusPayloadTest {

  @Test
  void exposesOnlyAggregateRuntimeState() {
    Map<String, Object> payload = HealthStatusPayload.from(
        Instant.parse("2026-07-25T00:00:00Z"),
        123_456L,
        17,
        3,
        2,
        1,
        512L,
        1_024L,
        2_048L,
        8);

    assertEquals("ok", payload.get("status"));
    assertEquals("2026-07-25T00:00:00Z", payload.get("timestamp"));
    assertEquals(123_456L, payload.get("uptimeMillis"));

    Map<?, ?> proxy = (Map<?, ?>) payload.get("proxy");
    assertEquals(17, proxy.get("onlinePlayers"));
    assertEquals(3, proxy.get("registeredServers"));

    Map<?, ?> backends = (Map<?, ?>) payload.get("backends");
    assertEquals(2, backends.get("observed"));
    assertEquals(1, backends.get("online"));

    Map<?, ?> jvm = (Map<?, ?>) payload.get("jvm");
    assertEquals(512L, jvm.get("heapUsedBytes"));
    assertEquals(1_024L, jvm.get("heapCommittedBytes"));
    assertEquals(2_048L, jvm.get("heapMaxBytes"));
    assertEquals(8, jvm.get("availableProcessors"));

    assertFalse(payload.containsKey("players"));
    assertFalse(payload.containsKey("servers"));
    assertFalse(payload.containsKey("apiKey"));
    assertFalse(payload.containsKey("addresses"));
  }

  @Test
  void clampsInvalidNegativeCounters() {
    Map<String, Object> payload = HealthStatusPayload.from(
        Instant.EPOCH,
        -1L,
        -1,
        -1,
        -1,
        -1,
        -1L,
        -1L,
        -1L,
        -1);

    assertEquals(0L, payload.get("uptimeMillis"));
    assertEquals(0, ((Map<?, ?>) payload.get("proxy")).get("onlinePlayers"));
    assertEquals(0, ((Map<?, ?>) payload.get("backends")).get("online"));
    assertEquals(0L, ((Map<?, ?>) payload.get("jvm")).get("heapUsedBytes"));
  }
}
