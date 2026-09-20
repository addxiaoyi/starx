package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {
  private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

  @Test
  void rejectsNewIdentityWhenCapacityIsFull() {
    LoginAttemptLimiter limiter = new LoginAttemptLimiter(2, 2, Duration.ofMinutes(1));

    assertTrue(limiter.allow(UUID.randomUUID(), NOW));
    assertTrue(limiter.allow(UUID.randomUUID(), NOW));
    assertFalse(limiter.allow(UUID.randomUUID(), NOW));
    assertEquals(2, limiter.size());
  }

  @Test
  void removesExpiredEntriesBeforeRejectingNewIdentity() {
    LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 2, Duration.ofMinutes(1));
    UUID first = UUID.randomUUID();

    assertTrue(limiter.allow(first, NOW));
    assertTrue(limiter.allow(UUID.randomUUID(), NOW.plus(Duration.ofMinutes(1))));
    assertEquals(1, limiter.size());
  }

  @Test
  void limitsRepeatedAttemptsForTheSameIdentity() {
    LoginAttemptLimiter limiter = new LoginAttemptLimiter(2, 2, Duration.ofMinutes(1));
    UUID player = UUID.randomUUID();

    assertTrue(limiter.allow(player, NOW));
    assertTrue(limiter.allow(player, NOW.plusSeconds(1)));
    assertFalse(limiter.allow(player, NOW.plusSeconds(2)));
    assertEquals(1, limiter.size());
  }
}
