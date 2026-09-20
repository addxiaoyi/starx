package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmailAddressTest {
  @Test
  void normalizesVerifiedMailboxBeforePersistence() {
    assertEquals("alex@example.com", EmailAddress.normalize(" Alex@Example.COM "));
  }

  @Test
  void rejectsMalformedOrOversizedMailbox() {
    assertThrows(IllegalArgumentException.class, () -> EmailAddress.normalize("alex"));
    assertThrows(IllegalArgumentException.class, () -> EmailAddress.normalize("@example.com"));
    assertThrows(IllegalArgumentException.class, () -> EmailAddress.normalize("alex@example"));
    assertThrows(IllegalArgumentException.class, () -> EmailAddress.normalize("a".repeat(250) + "@x.com"));
  }
}
