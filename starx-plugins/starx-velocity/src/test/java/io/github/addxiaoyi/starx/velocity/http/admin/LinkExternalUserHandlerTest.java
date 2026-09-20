package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

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

  @Test
  void trustsOnlyVerifiedMatchingIdentity() {
    UUID id = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    assertTrue(LinkExternalUserHandler.isTrustedBinding(id, "Steve", id.toString(), "steve", true));
    assertFalse(LinkExternalUserHandler.isTrustedBinding(id, "Steve", id.toString(), "Steve", false));
    assertFalse(LinkExternalUserHandler.isTrustedBinding(id, "Steve", UUID.randomUUID().toString(), "Steve", true));
    assertFalse(LinkExternalUserHandler.isTrustedBinding(id, "Steve", id.toString(), "Alex", true));
  }
}
