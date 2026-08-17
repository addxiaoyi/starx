package io.github.addxiaoyi.starx.velocity.module.welcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LatestWriteGateTest {

  @Test
  void rejectsAnOlderLoginWriteAfterANewerConnectionArrives() {
    LatestWriteGate<UUID> gate = new LatestWriteGate<>();
    UUID accountId = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    AtomicInteger writes = new AtomicInteger();
    LatestWriteGate<UUID>.Ticket older = gate.claim(accountId);
    LatestWriteGate<UUID>.Ticket latest = gate.claim(accountId);

    assertFalse(gate.run(older, writes::incrementAndGet));
    assertTrue(gate.run(latest, writes::incrementAndGet));
    assertEquals(1, writes.get());
  }

  @Test
  void releasesACompletedWriteSoTheNextLoginCanPersist() {
    LatestWriteGate<UUID> gate = new LatestWriteGate<>();
    UUID accountId = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    AtomicInteger writes = new AtomicInteger();

    assertTrue(gate.run(gate.claim(accountId), writes::incrementAndGet));
    assertTrue(gate.run(gate.claim(accountId), writes::incrementAndGet));
    assertEquals(2, writes.get());
  }
}
