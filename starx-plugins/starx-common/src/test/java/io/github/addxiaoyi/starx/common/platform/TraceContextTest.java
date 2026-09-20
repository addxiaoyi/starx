package io.github.addxiaoyi.starx.common.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TraceContextTest {
  @Test
  void preservesOneCorrelationIdAcrossChildOperations() {
    TraceContext root = TraceContext.create();

    assertEquals(root.correlationId(), root.child("skin-refresh").correlationId());
    assertEquals("skin-refresh", root.child("skin-refresh").operation());
  }

  @Test
  void rejectsBlankOperations() {
    assertThrows(IllegalArgumentException.class,
        () -> new TraceContext(UUID.randomUUID(), " "));
  }
}
