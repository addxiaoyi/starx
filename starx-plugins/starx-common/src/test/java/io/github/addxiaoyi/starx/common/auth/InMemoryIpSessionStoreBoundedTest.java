package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.model.IpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryIpSessionStoreBoundedTest {
  @Test
  void savePrunesExpiredAndEvictsOldestSession() {
    MutableClock clock = new MutableClock();
    InMemoryIpSessionStore store = new InMemoryIpSessionStore(clock, Duration.ofMinutes(30), 2);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    store.save(session(first, "10.0.0.1", 0));
    store.save(session(second, "10.0.0.2", 1));
    store.save(session(third, "10.0.0.3", 2));

    assertEquals(2, store.size());
    assertFalse(store.findByUuidAndIp(first, "10.0.0.1").isPresent());

    clock.advance(Duration.ofMinutes(31));
    store.save(session(first, "10.0.0.4", clock.millis()));
    assertEquals(1, store.size());
    assertTrue(store.findByUuidAndIp(first, "10.0.0.4").isPresent());
  }

  @Test
  void lookupRemovesSessionsPastRetentionWithoutAnotherSave() {
    MutableClock clock = new MutableClock();
    InMemoryIpSessionStore store = new InMemoryIpSessionStore(clock, Duration.ofMinutes(30), 2);
    UUID playerId = UUID.randomUUID();
    store.save(session(playerId, "10.0.0.1", clock.millis()));

    clock.advance(Duration.ofMinutes(31));

    assertFalse(store.findByUuidAndIp(playerId, "10.0.0.1").isPresent());
    assertEquals(0, store.size());
  }

  private static IpSession session(UUID playerId, String ip, long loginTime) {
    return new IpSession(playerId, ip, null, null, loginTime, "local");
  }

  private static final class MutableClock extends Clock {
    private long millis;
    void advance(Duration duration) { millis += duration.toMillis(); }
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
  }
}
