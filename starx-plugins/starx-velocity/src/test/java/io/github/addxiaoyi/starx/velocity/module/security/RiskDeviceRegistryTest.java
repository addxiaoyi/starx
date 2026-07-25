package io.github.addxiaoyi.starx.velocity.module.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RiskDeviceRegistryTest {

  @Test
  void evictsLeastRecentlyObservedPlayerAtCapacity() {
    RiskModule.DeviceRegistry registry = new RiskModule.DeviceRegistry(2);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    registry.observe(first, "10.0.0.1", 1);
    registry.observe(second, "10.0.0.2", 2);

    registry.observe(third, "10.0.0.3", 3);

    assertNull(registry.get(first));
    assertEquals(2, registry.size());
  }
}
