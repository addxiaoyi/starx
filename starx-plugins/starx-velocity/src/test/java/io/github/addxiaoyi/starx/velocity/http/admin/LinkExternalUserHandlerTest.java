package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LinkExternalUserHandlerTest {
  @Test
  void blankExternalIdentityMeansUnlink() {
    assertNull(LinkExternalUserHandler.normalizeExternalUserId("   "));
  }

  @Test
  void trimsExternalIdentityBeforePersistence() {
    assertEquals("site-user-7", LinkExternalUserHandler.normalizeExternalUserId("  site-user-7  "));
  }
}
