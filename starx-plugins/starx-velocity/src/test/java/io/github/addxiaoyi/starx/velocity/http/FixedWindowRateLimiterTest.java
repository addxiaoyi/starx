package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {
  @Test
  void resetsOnlyAfterTheWindowExpires() {
    MutableClock clock = new MutableClock();
    FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, 60_000, clock);

    assertTrue(limiter.tryAcquire());
    assertTrue(limiter.tryAcquire());
    assertFalse(limiter.tryAcquire());

    clock.advance(60_001);
    assertTrue(limiter.tryAcquire());
  }

  @Test
  void concurrentRequestsNeverExceedTheLimit() throws Exception {
    int requests = 64;
    int limit = 10;
    FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(limit, 60_000, Clock.systemUTC());
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger allowed = new AtomicInteger();

    try (var pool = Executors.newFixedThreadPool(16)) {
      for (int i = 0; i < requests; i++) {
        pool.submit(() -> {
          start.await();
          if (limiter.tryAcquire()) allowed.incrementAndGet();
          return null;
        });
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    assertEquals(limit, allowed.get());
  }

  private static final class MutableClock extends Clock {
    private long millis;

    void advance(long amount) {
      millis += amount;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }
  }
}
