package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PremiumProfileVerifierTest {
  private static final UUID PLAYER = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

  @Test
  void requiresBothMojangUuidAndName() {
    String profile = "{\"id\":\"123e4567e89b42d3a456426614174000\",\"name\":\"Steve\"}";
    assertTrue(PremiumProfileVerifier.matches(profile, PLAYER, "steve"));
    assertFalse(PremiumProfileVerifier.matches(profile, PLAYER, "Alex"));
    assertFalse(PremiumProfileVerifier.matches(
        profile.replace("123e4567e89b42d3a456426614174000", "00000000000000000000000000000000"),
        PLAYER, "Steve"));
  }

  @Test
  void rejectsMalformedOrIncompleteResponses() {
    assertFalse(PremiumProfileVerifier.matches("{}", PLAYER, "Steve"));
    assertFalse(PremiumProfileVerifier.matches("not-json", PLAYER, "Steve"));
    assertFalse(PremiumProfileVerifier.matches(null, PLAYER, "Steve"));
    assertFalse(PremiumProfileVerifier.matches(
        "{\"id\":\"123e4567e89b42d3a456426614174000\",\"name\":\"Steve!\"}",
        PLAYER, "Steve"));
    assertFalse(PremiumProfileVerifier.matches(
        "{\"id\":\"not-a-mojang-uuid\",\"name\":\"Steve\"}", PLAYER, "Steve"));
  }
}
