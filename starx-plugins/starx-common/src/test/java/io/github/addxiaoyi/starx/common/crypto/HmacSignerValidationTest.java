package io.github.addxiaoyi.starx.common.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HmacSignerValidationTest {
  @Test
  void acceptsOnlyFixedLengthHexSignatures() {
    String signature = HmacSigner.sign("secret", "body");

    assertTrue(HmacSigner.verify("secret", "body", signature));
    assertTrue(HmacSigner.verify("secret", "body", signature.toUpperCase()));
    assertFalse(HmacSigner.verify("secret", "body", "abc"));
    assertFalse(HmacSigner.verify("secret", "body", "g".repeat(64)));
    assertFalse(HmacSigner.verify("secret", "body", null));
  }

  @Test
  void constantTimeComparisonHandlesMissingValues() {
    assertTrue(HmacSigner.constantTimeEquals("same", "same"));
    assertFalse(HmacSigner.constantTimeEquals("same", "different"));
    assertFalse(HmacSigner.constantTimeEquals("same", null));
  }
}
