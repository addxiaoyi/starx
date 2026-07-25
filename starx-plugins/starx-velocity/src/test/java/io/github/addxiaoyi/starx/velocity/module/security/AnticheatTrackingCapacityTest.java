package io.github.addxiaoyi.starx.velocity.module.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AnticheatTrackingCapacityTest {

  @Test
  void trackingEvictsOldestPlayersAtCapacity() {
    AnticheatModule.TrackingRegistry registry = new AnticheatModule.TrackingRegistry(2);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    registry.track(first, "first", 1);
    registry.track(second, "second", 2);

    registry.track(third, "third", 3);

    assertEquals(2, registry.size());
    assertFalse(registry.contains(first));
  }
}
