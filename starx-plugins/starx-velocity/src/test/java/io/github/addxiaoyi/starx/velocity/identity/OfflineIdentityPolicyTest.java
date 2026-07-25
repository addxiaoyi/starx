package io.github.addxiaoyi.starx.velocity.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OfflineIdentityPolicyTest {

  @Test
  void classifiesConfiguredOfflinePrefixWithoutExternalPluginApis() {
    OfflineIdentityPolicy policy = new OfflineIdentityPolicy(".");

    assertTrue(policy.isPrefixed(".BedrockUser"));
    assertFalse(policy.isPrefixed("JavaUser"));
    assertFalse(policy.isPrefixed("."));
  }

  @Test
  void rejectsBlankOrWhitespacePrefixes() {
    assertThrows(IllegalArgumentException.class, () -> new OfflineIdentityPolicy(""));
    assertThrows(IllegalArgumentException.class, () -> new OfflineIdentityPolicy("   "));
  }
}
