package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigLayoutTest {

  @TempDir
  Path tempDir;

  @Test
  void splitEntryPointMergesFilesByConfiguredOrder() throws Exception {
    Path entrypoint = this.tempDir.resolve("config.yml");
    Files.writeString(entrypoint, """
        schema-version: 5
        config-files:
          directory: config
          files: [core.yml, auth.yml]
        """, StandardCharsets.UTF_8);
    write("config/core.yml", "api-key: core-key\n");
    write("config/auth.yml", "auth:\n  password-bypass-minutes: 9\n");

    StarxConfig config = ConfigLoader.load(entrypoint);

    assertEquals("core-key", config.apiKey());
    assertEquals(9, config.auth().passwordBypassMinutes());
    assertTrue(config.auth().premiumBypass());
    assertTrue(config.auth().floodgateBypass());
    assertTrue(config.auth().skinSiteBypass());
  }

  @Test
  void authenticationBypassFlagsAreLoadedFromAuthConfig() throws Exception {
    Path entrypoint = this.tempDir.resolve("config.yml");
    Files.writeString(entrypoint, """
        schema-version: 5
        config-files:
          directory: config
          files: [auth.yml]
        """, StandardCharsets.UTF_8);
    write("config/auth.yml", """
        auth:
          premium-bypass: false
          floodgate-bypass: false
          skin-site-bypass: false
        """);

    StarxConfig.AuthConfig auth = ConfigLoader.load(entrypoint).auth();

    assertAll(
        () -> assertFalse(auth.premiumBypass()),
        () -> assertFalse(auth.floodgateBypass()),
        () -> assertFalse(auth.skinSiteBypass()));
  }

  @Test
  void legacyMonolithicConfigIsReadAndMigratedWithAUsableBackup() throws Exception {
    Path entrypoint = this.tempDir.resolve("config.yml");
    Files.writeString(entrypoint, "schema-version: 5\napi-key: legacy\n", StandardCharsets.UTF_8);

    StarxConfig config = ConfigLoader.load(entrypoint);

    assertEquals("legacy", config.apiKey());
    assertTrue(Files.isRegularFile(this.tempDir.resolve("config/core.yml")));
    assertTrue(Files.readString(this.tempDir.resolve("config/core.yml"))
        .contains("api-key: legacy"));
    try (Stream<Path> files = Files.list(this.tempDir)) {
      assertTrue(files.anyMatch(path -> path.getFileName().toString()
          .startsWith("config.yml.split-backup")));
    }
    assertTrue(Files.readString(entrypoint).contains("config-files:"));
  }

  @Test
  void defaultUworldFileNameUsesTheRuntimeAssetDirectory() throws Exception {
    StarxConfig config = ConfigLoader.load(this.tempDir.resolve("config.yml"));

    assertEquals(
        "assets/uworld/auth_world.schem",
        config.uworld().auth().world().fileName());
  }

  @Test
  void rejectsUnsafeFragmentDeclarations() throws Exception {
    for (String files : List.of(
        "[../core.yml]",
        "[/tmp/core.yml]",
        "[core.yml, core.yml]",
        "[core.txt]",
        "[7]")) {
      Path entrypoint = this.tempDir.resolve("config-" + files.hashCode() + ".yml");
      Files.writeString(entrypoint, """
          schema-version: 5
          config-files:
            directory: config
            files: %s
          """.formatted(files), StandardCharsets.UTF_8);

      IllegalArgumentException error = assertThrows(
          IllegalArgumentException.class,
          () -> ConfigLoader.load(entrypoint));
      assertTrue(error.getMessage().contains("config-files"));
    }
  }

  private void write(String relative, String content) throws Exception {
    Path target = this.tempDir.resolve(relative);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
  }
}
