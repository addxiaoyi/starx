package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HmacReplayGuardTest {
  @Test
  void acceptsSignatureOnlyOnceDuringItsValidityWindow() {
    HmacReplayGuard guard = new HmacReplayGuard(2);

    assertTrue(guard.claim("signature-a", 1_000, 100));
    assertFalse(guard.claim("signature-a", 1_000, 200));
    assertEquals(1, guard.size());
  }

  @Test
  void expiredEntriesReleaseCapacity() {
    HmacReplayGuard guard = new HmacReplayGuard(1);

    assertTrue(guard.claim("signature-a", 1_000, 100));
    assertTrue(guard.claim("signature-b", 2_000, 1_000));
    assertEquals(1, guard.size());
  }

  @Test
  void rejectsNewSignatureWhenCapacityRemainsFull() {
    HmacReplayGuard guard = new HmacReplayGuard(1);

    assertTrue(guard.claim("signature-a", 1_000, 100));
    assertFalse(guard.claim("signature-b", 1_000, 200));
    assertEquals(1, guard.size());
  }

  @Test
  void rejectsAlreadyExpiredClaims() {
    HmacReplayGuard guard = new HmacReplayGuard(1);

    assertFalse(guard.claim("signature-a", 100, 100));
    assertEquals(0, guard.size());
  }
}
