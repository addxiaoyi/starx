package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendConfigSchemaUpgraderTest {
  private static final String DEFAULTS = """
      schema-version: 1
      compatibility:
        strict-platform: true
        report-file: compatibility-report.json
      bridge:
        enabled: true
        heartbeat:
          enabled: false
          api-key: ''
      """;

  @Test
  void upgradesLegacyConfigAndPreservesSecrets(@TempDir Path directory) throws Exception {
    Path config = directory.resolve("config.yml");
    Files.writeString(config, """
        bridge:
          enabled: false
          heartbeat:
            api-key: keep-this-secret
        """);
    Clock clock = Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC);

    BackendConfigSchemaUpgrader.UpgradeResult result =
        BackendConfigSchemaUpgrader.upgrade(config, DEFAULTS, clock, false, ignored -> {});

    assertTrue(result.changed());
    assertNotNull(result.backup());
    assertNotNull(result.report());
    assertTrue(Files.isRegularFile(result.backup()));
    YamlConfiguration loaded = YamlConfiguration.loadConfiguration(config.toFile());
    assertEquals(1, loaded.getInt("schema-version"));
    assertFalse(loaded.getBoolean("bridge.enabled"));
    assertEquals("keep-this-secret", loaded.getString("bridge.heartbeat.api-key"));
    assertTrue(loaded.getBoolean("compatibility.strict-platform"));
    String report = Files.readString(result.report());
    assertTrue(report.contains("compatibility.strict-platform"));
    assertFalse(report.contains("keep-this-secret"));
  }

  @Test
  void firstBootDoesNotCreateRedundantBackup(@TempDir Path directory) throws Exception {
    Path config = directory.resolve("config.yml");
    Files.writeString(config, DEFAULTS.replace("schema-version: 1\n", ""));
    BackendConfigSchemaUpgrader.UpgradeResult result =
        BackendConfigSchemaUpgrader.upgrade(
            config, DEFAULTS, Clock.systemUTC(), true, ignored -> {});
    assertTrue(result.changed());
    assertEquals(null, result.backup());
  }

  @Test
  void currentConfigIsIdempotent(@TempDir Path directory) throws Exception {
    Path config = directory.resolve("config.yml");
    Files.writeString(config, DEFAULTS);
    BackendConfigSchemaUpgrader.UpgradeResult result =
        BackendConfigSchemaUpgrader.upgrade(
            config, DEFAULTS, Clock.systemUTC(), false, ignored -> {});
    assertFalse(result.changed());
  }

  @Test
  void rejectsMalformedConfigWithoutChangingIt(@TempDir Path directory) throws Exception {
    Path config = directory.resolve("config.yml");
    String malformed = "bridge: [unterminated\n";
    Files.writeString(config, malformed);

    assertThrows(IllegalStateException.class, () ->
        BackendConfigSchemaUpgrader.upgrade(
            config, DEFAULTS, Clock.systemUTC(), false, ignored -> {}));
    assertEquals(malformed, Files.readString(config));
  }

  @Test
  void rejectsSectionScalarConflictsWithoutChangingThem(@TempDir Path directory)
      throws Exception {
    Path config = directory.resolve("config.yml");
    String conflicting = "bridge: false\n";
    Files.writeString(config, conflicting);

    assertThrows(IllegalStateException.class, () ->
        BackendConfigSchemaUpgrader.upgrade(
            config, DEFAULTS, Clock.systemUTC(), false, ignored -> {}));
    assertEquals(conflicting, Files.readString(config));
  }

  @Test
  void rejectsNonNumericSchemaVersion(@TempDir Path directory) throws Exception {
    Path config = directory.resolve("config.yml");
    Files.writeString(config, "schema-version: newest\n");
    assertThrows(IllegalStateException.class, () ->
        BackendConfigSchemaUpgrader.upgrade(
            config, DEFAULTS, Clock.systemUTC(), false, ignored -> {}));
  }

  @Test
  void rejectsFutureSchema(@TempDir Path directory) throws Exception {
    Path config = directory.resolve("config.yml");
    Files.writeString(config, "schema-version: 99\n");
    assertThrows(IllegalStateException.class, () ->
        BackendConfigSchemaUpgrader.upgrade(
            config, DEFAULTS, Clock.systemUTC(), false, ignored -> {}));
  }
}
