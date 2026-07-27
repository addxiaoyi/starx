package io.github.addxiaoyi.starx.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebsiteSyncCoreTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void secretsAreAlwaysRedacted() {
    SecretValue secret = SecretValue.of("stx_node_private");
    assertTrue(secret.isPresent());
    assertEquals("[REDACTED]", secret.toString());
    assertFalse(secret.toString().contains("private"));
  }

  @Test
  void configRejectsUnsafeUrlsAndInvalidBounds() {
    assertThrows(IllegalArgumentException.class, () -> new WebsiteSyncConfig(
        true,
        URI.create("file:///tmp/token"),
        "proxy-1",
        WebsitePlatform.VELOCITY,
        SecretValue.empty(),
        SecretValue.empty(),
        WebsiteSyncConfig.Heartbeat.defaults(),
        WebsiteSyncConfig.Textures.defaults()));
    assertThrows(IllegalArgumentException.class, () -> new WebsiteSyncConfig.Heartbeat(
        1, 3_000, 8_000));
    assertThrows(IllegalArgumentException.class, () -> new WebsiteSyncConfig.Textures(
        true, "skinsrestorer", 300, 1_001));
  }

  @Test
  void nodeAndServerSnapshotsPreserveUnknownValuesAsNull() {
    ServerSnapshot child = new ServerSnapshot(
        "survival-1",
        "原版生存",
        "paper",
        null,
        WebsiteNodeStatus.UNKNOWN,
        null,
        null,
        null,
        null,
        false,
        List.of(NodeCapabilities.SERVER_STATUS));
    NodeSnapshot node = new NodeSnapshot(
        "0.2.0", null, 1, 100, null, null, false, List.of(child));

    assertEquals(null, child.minecraftVersion());
    assertEquals(null, child.tps());
    assertEquals(null, node.minecraftVersion());
    assertEquals(WebsiteNodeStatus.OFFLINE, node.offline().servers().getFirst().status());
  }

  @Test
  void credentialStoreUpdatesOnlyWebsiteCredentialScalars() throws Exception {
    Path config = this.temporaryDirectory.resolve("config.yml");
    Files.writeString(config, """
        schema-version: 2
        website-sync:
          enabled: true
          site-url: "https://star-web.top"
          node-id: "proxy-1"
          platform: "velocity"
          # one-time token
          bootstrap-token: "stx_boot_old"
          node-token: ""
          heartbeat:
            interval-seconds: 15
        auth:
          enabled: true
        """, StandardCharsets.UTF_8);

    new YamlWebsiteCredentialStore(config).persistEnrollment(SecretValue.of("stx_node_new"));
    String updated = Files.readString(config, StandardCharsets.UTF_8);

    assertTrue(updated.contains("bootstrap-token: \"\""));
    assertTrue(updated.contains("node-token: \"stx_node_new\""));
    assertTrue(updated.contains("# one-time token"));
    assertTrue(updated.contains("auth:"));
    assertFalse(Files.list(this.temporaryDirectory)
        .anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
  }
}
