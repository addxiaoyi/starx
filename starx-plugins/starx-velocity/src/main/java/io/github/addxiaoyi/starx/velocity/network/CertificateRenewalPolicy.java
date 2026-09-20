package io.github.addxiaoyi.starx.velocity.network;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Determines whether a managed certificate is missing, invalid, or inside its renewal window. */
final class CertificateRenewalPolicy {

  private CertificateRenewalPolicy() {
  }

  static Decision evaluate(
      Path fullChain,
      Instant now,
      int renewBeforeDays) {
    Objects.requireNonNull(now, "now");
    if (renewBeforeDays < 1 || renewBeforeDays > 60) {
      throw new IllegalArgumentException("renewBeforeDays must be between 1 and 60");
    }
    if (fullChain == null || !Files.isRegularFile(fullChain)) {
      return new Decision(Status.MISSING, true, null, "certificate file is missing");
    }

    try (InputStream input = Files.newInputStream(fullChain)) {
      X509Certificate certificate = (X509Certificate) CertificateFactory
          .getInstance("X.509")
          .generateCertificate(input);
      Instant notAfter = certificate.getNotAfter().toInstant();
      Instant renewAt = notAfter.minus(Duration.ofDays(renewBeforeDays));
      boolean due = !now.isBefore(renewAt);
      return new Decision(
          due ? Status.DUE : Status.VALID,
          due,
          notAfter,
          due
              ? "certificate is inside the configured renewal window"
              : "certificate is outside the configured renewal window");
    } catch (IOException | CertificateException | RuntimeException error) {
      return new Decision(
          Status.INVALID,
          true,
          null,
          "certificate file cannot be parsed and should be replaced");
    }
  }

  record Decision(
      Status status,
      boolean due,
      Instant notAfter,
      String reason) {
    Decision {
      status = Objects.requireNonNull(status, "status");
      reason = Objects.requireNonNull(reason, "reason");
    }
  }

  enum Status {
    MISSING,
    INVALID,
    DUE,
    VALID
  }
}
