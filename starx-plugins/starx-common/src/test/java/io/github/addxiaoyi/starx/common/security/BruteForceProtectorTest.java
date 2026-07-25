package io.github.addxiaoyi.starx.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BruteForceProtectorTest {
  @Test
  void returnedWaitTimeIsImmutableAcrossPlayers() {
    MutableClock clock = new MutableClock();
    BruteForceProtector protector = new BruteForceProtector(clock);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    protector.recordFailure(first);
    protector.recordFailure(second);
    protector.recordFailure(second);

    BruteForceProtector.Check firstCheck = protector.check(first);
    clock.advance(250);
    protector.check(second);

    assertEquals(BruteForceProtector.BruteForceStatus.DELAYED, firstCheck.status());
    assertEquals(1_000, firstCheck.waitMs());
  }

  @Test
  void failureRecordsAreBoundedAndExpiredEntriesArePruned() {
    MutableClock clock = new MutableClock();
    BruteForceProtector protector = new BruteForceProtector(clock, 2);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    protector.recordFailure(first);
    clock.advance(1);
    protector.recordFailure(second);
    clock.advance(1);
    protector.recordFailure(third);

    assertEquals(0, protector.getAttemptCount(first));
    assertEquals(2, protector.trackedPlayers());

    clock.advance(900_001);
    protector.recordFailure(first);
    assertEquals(1, protector.trackedPlayers());
  }

  private static final class MutableClock extends Clock {
    private long millis;

    void advance(long amount) { millis += amount; }
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
  }
}
