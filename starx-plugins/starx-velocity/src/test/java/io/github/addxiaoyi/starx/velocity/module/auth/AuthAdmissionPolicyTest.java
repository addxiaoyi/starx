package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AuthAdmissionPolicyTest {

  @Test
  void premiumLoginRequiresThePremiumBypassFlag() {
    assertTrue(AuthAdmissionPolicy.isPremiumAutoLogin(true, true));
    assertFalse(AuthAdmissionPolicy.isPremiumAutoLogin(true, false));
    assertFalse(AuthAdmissionPolicy.isPremiumAutoLogin(false, true));
  }

  @Test
  void floodgateLoginRequiresTheFloodgateBypassFlag() {
    assertTrue(AuthAdmissionPolicy.isFloodgateAutoLogin(true, true));
    assertFalse(AuthAdmissionPolicy.isFloodgateAutoLogin(true, false));
    assertFalse(AuthAdmissionPolicy.isFloodgateAutoLogin(false, true));
  }

  @Test
  void websiteBindingLoginRequiresTheSkinSiteBypassFlag() {
    assertTrue(AuthAdmissionPolicy.isSkinSiteAutoLogin(true, true));
    assertFalse(AuthAdmissionPolicy.isSkinSiteAutoLogin(true, false));
    assertFalse(AuthAdmissionPolicy.isSkinSiteAutoLogin(false, true));
  }
}
