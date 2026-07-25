package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AuthAdmissionPolicyTest {

  @Test
  void autoLoginRequiresPremiumOrTrustedExternalIdentity() {
    assertTrue(AuthAdmissionPolicy.canAutoLogin(true, false));
    assertTrue(AuthAdmissionPolicy.canAutoLogin(false, true));
    assertFalse(AuthAdmissionPolicy.canAutoLogin(false, false));
  }
}
