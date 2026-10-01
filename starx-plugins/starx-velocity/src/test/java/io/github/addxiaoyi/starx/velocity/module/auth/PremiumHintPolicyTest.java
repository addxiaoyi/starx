package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PremiumHintPolicyTest {

  @Test
  void hintsOnlyWhenFastLoginCanStillUnlockThePlayer() {
    assertTrue(PremiumHintPolicy.shouldHint(true, false, false));
  }

  @Test
  void staysQuietWithoutFastLogin() {
    assertFalse(PremiumHintPolicy.shouldHint(false, false, false));
  }

  @Test
  void staysQuietForVerifiedOrAlreadyHintedPlayers() {
    assertFalse(PremiumHintPolicy.shouldHint(true, true, false));
    assertFalse(PremiumHintPolicy.shouldHint(true, false, true));
  }
}