package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ReconnectTargetStoreTest {

  @Test
  void targetIsConsumedOnlyOnce() {
    ReconnectTargetStore store = new ReconnectTargetStore(4);
    UUID playerId = UUID.randomUUID();
    store.remember(playerId, "survival", 1);

    assertEquals("survival", store.consume(playerId).orElseThrow());
    assertTrue(store.consume(playerId).isEmpty());
  }

  @Test
  void evictsOldestPendingTargetAtCapacity() {
    ReconnectTargetStore store = new ReconnectTargetStore(2);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    store.remember(first, "one", 1);
    store.remember(second, "two", 2);

    store.remember(third, "three", 3);

    assertTrue(store.consume(first).isEmpty());
    assertEquals(2, store.size());
  }
}
