package io.github.addxiaoyi.starx.common.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

final class TotpGeneratorTest {

  @Test
  void matchesTheProbeForTheFullLengthFixtureSecret() {
    assertEquals(
        "775917",
        TotpGenerator.generate(
            "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
            Instant.parse("2026-07-20T05:17:02.200Z")));
  }
}
