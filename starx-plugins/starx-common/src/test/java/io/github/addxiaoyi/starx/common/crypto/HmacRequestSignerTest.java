package io.github.addxiaoyi.starx.common.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HmacRequestSignerTest {
  @Test
  void bindsSignatureToMethodPathTimestampAndBody() {
    String signature = HmacRequestSigner.sign(
        "secret", "POST", "/v1/admin/ban", "1784782800000", "{\"name\":\"Alex\"}");

    assertTrue(HmacRequestSigner.verify(
        "secret", "POST", "/v1/admin/ban", "1784782800000",
        "{\"name\":\"Alex\"}", signature));
    assertFalse(HmacRequestSigner.verify(
        "secret", "POST", "/v1/admin/delete-user", "1784782800000",
        "{\"name\":\"Alex\"}", signature));
    assertFalse(HmacRequestSigner.verify(
        "secret", "GET", "/v1/admin/ban", "1784782800000",
        "{\"name\":\"Alex\"}", signature));
    assertFalse(HmacRequestSigner.verify(
        "secret", "POST", "/v1/admin/ban", "1784782800001",
        "{\"name\":\"Alex\"}", signature));
  }

  @Test
  void rejectsCanonicalFieldsContainingLineBreaks() {
    assertFalse(HmacRequestSigner.verify(
        "secret", "POST\nGET", "/v1/admin/ban", "1784782800000", "{}", "0".repeat(64)));
  }
}
