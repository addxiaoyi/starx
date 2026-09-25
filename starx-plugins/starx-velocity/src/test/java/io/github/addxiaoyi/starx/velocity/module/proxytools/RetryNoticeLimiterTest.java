package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryNoticeLimiterTest {
  @Test
  void persistentFailuresStillProduceOneNoticeEachCooldown() {
    var limiter = new RetryNoticeLimiter();
    UUID id = UUID.randomUUID();
    for (int second = 0; second <= 45; second++) {
      assertEquals(second % 15 == 0, limiter.allow(id, Duration.ofSeconds(second).toNanos()));
    }
  }

  @Test
  void clearingPlayerAllowsANewSessionToReceiveItsFirstNotice() {
    var limiter = new RetryNoticeLimiter();
    UUID id = UUID.randomUUID();
    assertTrue(limiter.allow(id, 0));
    assertFalse(limiter.allow(id, 1));
    limiter.remove(id);
    assertTrue(limiter.allow(id, 2));
    limiter.clear();
    assertTrue(limiter.allow(id, 3));
  }

  @Test
  void simultaneousFailuresDoNotDuplicateNotices() throws Exception {
    var limiter = new RetryNoticeLimiter();
    UUID id = UUID.randomUUID();
    AtomicInteger notices = new AtomicInteger();
    try (var workers = Executors.newFixedThreadPool(16)) {
      for (int i = 0; i < 1000; i++) workers.submit(() -> {
        if (limiter.allow(id, 0)) notices.incrementAndGet();
      });
      workers.shutdown();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
    assertEquals(1, notices.get());
  }
}
