package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

final class ConfigSchemaUpgraderTest {

  @TempDir
  Path tempDir;

  @Test
  void upgradesOldConfigWithBackupReportAndDeepDefaults() throws Exception {
    Path config = this.tempDir.resolve("config.yml");
    String original = """
        auth:
          allow-offline-default: true
        custom-root:
          retained: "yes"
        """;
    Files.writeString(config, original, StandardCharsets.UTF_8);

    Map<String, Object> current = root(original);
    Map<String, Object> defaults = root("""
        schema-version: 5
        auth:
          allow-offline-default: false
          ux:
            titles-enabled: true
        modules:
          starx.auth:
            enabled: true
        """);
    List<String> warnings = new ArrayList<>();

    ConfigSchemaUpgrader.UpgradeResult result = ConfigSchemaUpgrader.upgrade(
        config,
        current,
        defaults,
        warnings::add,
        Clock.fixed(Instant.parse("2026-07-26T01:00:00Z"), ZoneOffset.UTC));

    assertTrue(result.changed());
    assertEquals(0, result.sourceVersion());
    assertEquals(5, result.targetVersion());
    assertTrue(Files.isRegularFile(result.backup()));
    assertTrue(Files.isRegularFile(result.report()));
    assertEquals(original, Files.readString(result.backup()));
    assertTrue(result.addedPaths().contains("auth.ux"));
    assertTrue(result.addedPaths().contains("modules"));
    assertEquals(1, warnings.size());

    Map<String, Object> upgraded = root(Files.readString(config));
    assertEquals(5, upgraded.get("schema-version"));
    assertEquals(true, mapping(upgraded.get("auth")).get("allow-offline-default"));
    assertEquals(
        true,
        mapping(mapping(upgraded.get("auth")).get("ux")).get("titles-enabled"));
    assertEquals("yes", mapping(upgraded.get("custom-root")).get("retained"));
    assertTrue(Files.readString(result.report()).contains("\"fromSchema\": 0"));
    assertTrue(Files.readString(result.report()).contains("\"toSchema\": 5"));
  }

  @Test
  void currentCompleteSchemaDoesNotRewriteOrCreateBackup() throws Exception {
    Path config = this.tempDir.resolve("config.yml");
    String yaml = """
        schema-version: 5
        auth:
          allow-offline-default: false
        """;
    Files.writeString(config, yaml, StandardCharsets.UTF_8);
    Instant before = Files.getLastModifiedTime(config).toInstant();

    ConfigSchemaUpgrader.UpgradeResult result = ConfigSchemaUpgrader.upgrade(
        config,
        root(yaml),
        root(yaml),
        ignored -> {
          throw new AssertionError("No warning expected");
        },
        Clock.fixed(Instant.parse("2026-07-26T01:00:00Z"), ZoneOffset.UTC));

    assertFalse(result.changed());
    assertEquals(before, Files.getLastModifiedTime(config).toInstant());
    assertEquals(yaml, Files.readString(config));
    assertEquals(null, result.backup());
    assertEquals(null, result.report());
  }

  @Test
  void futureSchemaIsRejectedBeforeAnyWrite() throws Exception {
    Path config = this.tempDir.resolve("config.yml");
    String yaml = "schema-version: 999\n";
    Files.writeString(config, yaml, StandardCharsets.UTF_8);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> ConfigSchemaUpgrader.upgrade(
            config,
            root(yaml),
            root("schema-version: 5\n"),
            ignored -> { }));

    assertTrue(error.getMessage().contains("newer than supported"));
    assertEquals(yaml, Files.readString(config));
    assertEquals(1, Files.list(this.tempDir).count());
  }

  @Test
  void generatedConfigUsesCurrentSchema() throws Exception {
    Path config = this.tempDir.resolve("generated.yml");

    StarxConfig loaded = ConfigLoader.load(config);

    assertNotNull(loaded);
    assertEquals(5, root(Files.readString(config)).get("schema-version"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> root(String yaml) {
    return new LinkedHashMap<>((Map<String, Object>) new Yaml().load(yaml));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapping(Object value) {
    return (Map<String, Object>) value;
  }
}
