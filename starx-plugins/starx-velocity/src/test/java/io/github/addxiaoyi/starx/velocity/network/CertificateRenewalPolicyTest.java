package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CertificateRenewalPolicyTest {
  static final String VALID_CERTIFICATE = """
      -----BEGIN CERTIFICATE-----
      MIIC5jCCAc6gAwIBAgIURGw5J2l1Q5ed161y4CIx2IEweTgwDQYJKoZIhvcNAQEL
      BQAwHDEaMBgGA1UEAwwRcGFuZWwuZXhhbXBsZS5jb20wHhcNMjUwMTAxMDAwMDAw
      WhcNMzUwMTAxMDAwMDAwWjAcMRowGAYDVQQDDBFwYW5lbC5leGFtcGxlLmNvbTCC
      ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBANo/ajH8Bk3e4Tjue6BHA1s/
      zfqixRMe+REYRCEnHkaV+AFr0M4X+PAd2b+VqK5xmVfJjlP8DOnuPJ0Jh2GYIzyn
      6Efxu4R0AR7LVTrGu5uSJVMUJpdLhLPtzTAjif2eB7zaRmAu7TP/pHD9mbcGQTrW
      ebCJ2QeQEGdTecypTKhsQib6rmDEkgGCqllYupFelVOiw+hZtOkNBbSjr0P3kcTI
      SmIJtuWFCVEMDe6QDwMieibgGfa9Gu99mOQZpPonvUuYFQb4ejSOuWPO7hMZCsNO
      rR5D5WdBiieAyztpvQTQU9z8cWIbAV5/phgA54tXELGEMcXYXdL+LtqvwRfUI2cC
      AwEAAaMgMB4wHAYDVR0RBBUwE4IRcGFuZWwuZXhhbXBsZS5jb20wDQYJKoZIhvcN
      AQELBQADggEBAH3NyjJHIYrKoHkImDgWIV1IYeiG5HZyl9Vm0sF2eTYe630N9mHW
      +17rwgo5c+pZ92Yq9hr4nGraEcfUcpT6sf7S/UzN6QdKX6ArDRQwz/k9oP6NeqQE
      kNrVI7zuD70lxVeCQPS0dcUWEVT5pws00DC7jVYf8CbmpxOLuaJGRP7566vEkEX6
      GILGRiEQlSf3Aw+2vhMbIZQM3Ed+k2z0ziVIszG/39XWKAl3XFlmP0rL5kqEHHiB
      E7s/QTv/0EOkpi36qiSF5vNS+/ze09xYYYf0aUubX1eCR1hhBYpiBrY//vKjzcnX
      9+JIuYyZJUGQ2GqskJ/QIeiRFA3ebSx7V04=
      -----END CERTIFICATE-----
      """;

  @TempDir
  Path temporary;

  @Test
  void missingAndInvalidCertificatesAreDueForReplacement() throws Exception {
    Path missing = this.temporary.resolve("missing.pem");
    CertificateRenewalPolicy.Decision missingDecision =
        CertificateRenewalPolicy.evaluate(
            missing, Instant.parse("2026-07-29T00:00:00Z"), 30);
    assertEquals(CertificateRenewalPolicy.Status.MISSING, missingDecision.status());
    assertTrue(missingDecision.due());
    assertNull(missingDecision.notAfter());

    Path invalid = this.temporary.resolve("invalid.pem");
    Files.writeString(invalid, "not a certificate");
    CertificateRenewalPolicy.Decision invalidDecision =
        CertificateRenewalPolicy.evaluate(
            invalid, Instant.parse("2026-07-29T00:00:00Z"), 30);
    assertEquals(CertificateRenewalPolicy.Status.INVALID, invalidDecision.status());
    assertTrue(invalidDecision.due());
  }

  @Test
  void honorsConfiguredRenewalWindow() throws Exception {
    Path certificate = this.temporary.resolve("fullchain.pem");
    Files.writeString(certificate, VALID_CERTIFICATE);

    CertificateRenewalPolicy.Decision valid = CertificateRenewalPolicy.evaluate(
        certificate, Instant.parse("2034-11-01T00:00:00Z"), 30);
    assertEquals(CertificateRenewalPolicy.Status.VALID, valid.status());
    assertFalse(valid.due());
    assertEquals(Instant.parse("2035-01-01T00:00:00Z"), valid.notAfter());

    CertificateRenewalPolicy.Decision due = CertificateRenewalPolicy.evaluate(
        certificate, Instant.parse("2034-12-15T00:00:00Z"), 30);
    assertEquals(CertificateRenewalPolicy.Status.DUE, due.status());
    assertTrue(due.due());
  }
}
