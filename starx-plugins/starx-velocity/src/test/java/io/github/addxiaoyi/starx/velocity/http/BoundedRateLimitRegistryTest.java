package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BoundedRateLimitRegistryTest {
  @Test
  void rejectsNewIdentityAtCapacityWithoutEvictingExistingIdentity() {
    Clock clock = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);
    BoundedRateLimitRegistry registry = new BoundedRateLimitRegistry(2, 1, 60_000L, clock);

    assertTrue(registry.tryAcquire("one"));
    assertTrue(registry.tryAcquire("two"));
    assertFalse(registry.tryAcquire("three"));
    assertFalse(registry.tryAcquire("one"));
  }
}
