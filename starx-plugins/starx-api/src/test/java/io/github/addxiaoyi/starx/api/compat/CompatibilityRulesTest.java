package io.github.addxiaoyi.starx.api.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompatibilityRulesTest {
  @Test
  void classifiesJavaRuntime() {
    assertEquals(CompatibilityStatus.SUPPORTED,
        CompatibilityRules.javaRuntime("21.0.8").status());
    assertEquals(CompatibilityStatus.UNSUPPORTED,
        CompatibilityRules.javaRuntime("17.0.15").status());
    assertEquals(CompatibilityStatus.DEGRADED,
        CompatibilityRules.javaRuntime("22.0.2").status());
    assertEquals(CompatibilityStatus.SUPPORTED,
        CompatibilityRules.javaRuntime("25.0.4").status());
  }

  @Test
  void enforcesCertifiedVelocityBuild() {
    assertEquals(CompatibilityStatus.SUPPORTED,
        CompatibilityRules.velocityRuntime("3.5.0-SNAPSHOT (git-test-b606)").status());
    assertEquals(CompatibilityStatus.UNSUPPORTED,
        CompatibilityRules.velocityRuntime("3.5.0-SNAPSHOT (git-test-b607)").status());
    assertEquals(CompatibilityStatus.DEGRADED,
        CompatibilityRules.velocityRuntime("3.5.0-SNAPSHOT").status());
  }

  @Test
  void classifiesMinecraftPatchRange() {
    assertEquals(CompatibilityStatus.SUPPORTED,
        CompatibilityRules.minecraftRuntime("1.21.11").status());
    assertEquals(CompatibilityStatus.DEGRADED,
        CompatibilityRules.minecraftRuntime("1.21.12").status());
    assertEquals(CompatibilityStatus.SUPPORTED,
        CompatibilityRules.minecraftRuntime("26.1.2").status());
    assertEquals(CompatibilityStatus.SUPPORTED,
        CompatibilityRules.minecraftRuntime("26.2.0").status());
    assertEquals(CompatibilityStatus.DEGRADED,
        CompatibilityRules.minecraftRuntime("26.3.0").status());
    assertEquals(CompatibilityStatus.UNSUPPORTED,
        CompatibilityRules.minecraftRuntime("27.0.0").status());
  }

  @Test
  void classifiesOptionalIntegrations() {
    assertEquals(CompatibilityStatus.SUPPORTED,
        CompatibilityRules.integration("skinsrestorer", "SkinsRestorer", "15.12.0").status());
    assertEquals(CompatibilityStatus.DEGRADED,
        CompatibilityRules.integration("skinsrestorer", "SkinsRestorer", "16.0.0").status());
    assertEquals(CompatibilityStatus.SUPPORTED,
        CompatibilityRules.integration("tab", "TAB", "6.0.2").status());
    assertEquals(CompatibilityStatus.SUPPORTED,
        CompatibilityRules.integration("luckperms", "LuckPerms", "").status());
    CompatibilityCheck sanitized = CompatibilityRules.integration(
        "tab", "TAB", "6.0.2\nforged-log-line" + "x".repeat(600));
    assertFalse(sanitized.detectedVersion().contains("\n"));
    assertTrue(sanitized.detectedVersion().length() <= 512);
  }

  @Test
  void handlesOversizedVersionNumbersSafely() {
    assertEquals(CompatibilityStatus.UNKNOWN,
        CompatibilityRules.integration(
            "tab", "TAB", "999999999999999999999999999999").status());
    assertEquals(CompatibilityStatus.DEGRADED,
        CompatibilityRules.velocityRuntime(
            "3.5.0-SNAPSHOT (git-test-b999999999999999999999999999999)").status());
  }

  @Test
  void writesSecretFreeAtomicReport(@TempDir Path directory) throws Exception {
    CompatibilityReport report = new CompatibilityReport(
        "velocity",
        "3.5.0 build 606",
        "21.0.8",
        Instant.parse("2026-07-27T00:00:00Z"),
        List.of(new CompatibilityCheck(
            "velocity",
            "Velocity",
            "3.5.0 build 606",
            "3.5.0 build 606",
            CompatibilityStatus.SUPPORTED,
            "validated")));
    Path target = directory.resolve("compatibility-report.json");
    report.writeAtomically(target);
    String json = Files.readString(target);
    assertTrue(json.contains("\"overallStatus\": \"SUPPORTED\""));
    assertFalse(json.toLowerCase().contains("token"));
    assertFalse(json.toLowerCase().contains("api-key"));
    assertEquals(CompatibilityStatus.SUPPORTED, report.overallStatus());
  }
}
