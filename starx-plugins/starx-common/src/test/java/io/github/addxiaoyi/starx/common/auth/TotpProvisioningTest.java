package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

final class TotpProvisioningTest {

  @Test
  void createsAuthenticatorCompatibleUri() {
    URI uri = TotpProvisioning.uri("StarMC", "add 玩家", "JBSWY3DPEHPK3PXP");

    assertEquals("otpauth", uri.getScheme());
    assertEquals("totp", uri.getAuthority());
    assertEquals("/StarMC%3Aadd%20%E7%8E%A9%E5%AE%B6", uri.getRawPath());
    assertTrue(uri.getRawQuery().contains("secret=JBSWY3DPEHPK3PXP"));
    assertTrue(uri.getRawQuery().contains("issuer=StarMC"));
    assertTrue(uri.getRawQuery().contains("digits=6"));
    assertTrue(uri.getRawQuery().contains("period=30"));
  }
}
