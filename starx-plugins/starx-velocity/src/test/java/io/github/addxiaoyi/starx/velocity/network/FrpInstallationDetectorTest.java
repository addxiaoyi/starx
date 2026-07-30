package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrpInstallationDetectorTest {
  @TempDir
  Path temporary;

  @Test
  void explicitConfigRemainsAuthoritativeWhenMissing() {
    NetworkAutomationConfig.Frp config = config("missing/frpc.toml");

    FrpInstallationDetector.Result result = FrpInstallationDetector.detect(
        config,
        this.temporary,
        List.of());

    assertEquals(FrpInstallationDetector.Source.EXPLICIT_CONFIG, result.source());
    assertFalse(result.configPresent());
    assertEquals(this.temporary.resolve("missing/frpc.toml").toAbsolutePath().normalize(),
        result.mainConfig());
  }

  @Test
  void discoversConfigFromRunningFrpcArguments() throws Exception {
    Path configFile = this.temporary.resolve("runtime/frpc.toml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, "serverAddr = \"frp.example.com\"\n");
    FrpInstallationDetector.ProcessCandidate process =
        new FrpInstallationDetector.ProcessCandidate(
            Path.of("C:/frp/frpc.exe"),
            List.of("-c", "runtime/frpc.toml"),
            this.temporary);

    FrpInstallationDetector.Result result = FrpInstallationDetector.detect(
        config(""),
        this.temporary,
        List.of(process));

    assertEquals(FrpInstallationDetector.Source.RUNNING_PROCESS, result.source());
    assertTrue(result.configPresent());
    assertEquals(configFile.toAbsolutePath().normalize(), result.mainConfig());
    assertEquals(Path.of("C:/frp/frpc.exe").toString(), result.command());
  }

  @Test
  void checksOnlyBoundedKnownLocations() throws Exception {
    Path configFile = this.temporary.resolve("frpc.toml");
    Files.writeString(configFile, "serverAddr = \"frp.example.com\"\n");

    FrpInstallationDetector.Result result = FrpInstallationDetector.detect(
        config(""),
        this.temporary,
        List.of());

    assertEquals(FrpInstallationDetector.Source.KNOWN_LOCATION, result.source());
    assertEquals(configFile.toAbsolutePath().normalize(), result.mainConfig());
    assertTrue(result.configPresent());
  }

  private static NetworkAutomationConfig.Frp config(String mainConfig) {
    return new NetworkAutomationConfig.Frp(
        NetworkAutomationConfig.Frp.Mode.DETECT,
        "frp.example.com",
        "http",
        "",
        "starx-api",
        "127.0.0.1",
        8788,
        0,
        "frpc",
        mainConfig,
        "frp/starx-api.toml",
        false);
  }
}
