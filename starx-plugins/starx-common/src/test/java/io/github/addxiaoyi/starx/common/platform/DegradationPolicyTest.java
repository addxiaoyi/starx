package io.github.addxiaoyi.starx.common.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DegradationPolicyTest {
  private final DegradationPolicy policy = new DegradationPolicy();

  @Test
  void websiteOutageKeepsLocalAuthentication() {
    assertEquals(
        DegradationPolicy.Fallback.LOCAL_PASSWORD_AND_TOTP,
        policy.choose(DegradationPolicy.Service.WEBSITE, false, true));
  }

  @Test
  void skinOutageUsesVerifiedCacheBeforeBundledDefault() {
    assertEquals(
        DegradationPolicy.Fallback.VERIFIED_SKIN_CACHE,
        policy.choose(DegradationPolicy.Service.SKIN, false, true));
    assertEquals(
        DegradationPolicy.Fallback.BUNDLED_DEFAULT_SKIN,
        policy.choose(DegradationPolicy.Service.SKIN, false, false));
  }
}
