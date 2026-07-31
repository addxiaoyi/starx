package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

final class ConfigLoaderUworldTest {

  private static final String NEW_CONFIG = """
      modules:
        starx.uworld:
          enabled: true
      uworld:
        enabled: true
        transfer-timeout-seconds: 19
        auth:
          timeout-seconds: 301
          target-server: "  new-hub  "
          world:
            dimension: "overworld"
            spawn-x: 1.5
            spawn-y: 101.0
            spawn-z: 2.5
            spawn-yaw: 10.0
            spawn-pitch: 20.0
            game-mode: "survival"
            loader-type: "void"
            file-name: "auth_world.schem"
            offset-x: 1
            offset-y: 2
            offset-z: 3
            view-distance: 5
            simulation-distance: 6
            platform-radius: 7
            platform-size: 63
        diagnostics:
          enabled: false
          timeout-seconds: 121
          platform-radius: 8
      """;

  private static final String LEGACY_ROOT = """
      limbo:
        enabled: true
        dimension: "nether"
        spawn-x: 0.5
        spawn-y: 100.0
        spawn-z: 0.75
        spawn-yaw: 11.0
        spawn-pitch: 12.0
        game-mode: "adventure"
        world-loader-type: "schematic"
        world-file-name: "legacy_auth.schem"
        world-offset-x: 4
        world-offset-y: 5
        world-offset-z: 6
        auth-timeout-seconds: 302
        hub-server: "legacy-hub"
        view-distance: 7
        simulation-distance: 8
        platform-size: 9
      """;

  private static final String LEGACY_CONFIG = """
      modules:
        starx.limbo:
          enabled: true
      """ + LEGACY_ROOT;

  private static final String BOTH_CONFIG = NEW_CONFIG + "\n" + LEGACY_ROOT;
  private static final String BOTH_ROOTS_WARNING =
      "Both uworld and legacy limbo configuration are present; uworld takes precedence";
  private static final String LEGACY_WARNING =
      "Legacy limbo configuration is deprecated; migrate to uworld";
  private static final String SCHEMA_WARNING_PREFIX =
      "StarX configuration upgraded from schema ";

  @TempDir
  Path tempDir;

  @Test
  void rejectsTwoQueueImplementationsEnabledAtOnce() {
    assertInvalidConfig("""
        modules:
          starx.queue:
            enabled: true
          starx.proxytools.smart-queue:
            enabled: true
        """, "starx.queue");
  }

  @Test
  void newRootWinsAndAllFieldsAreParsed() throws Exception {
    List<String> warnings = new ArrayList<>();

    StarxConfig config = load(NEW_CONFIG, warnings);
    UworldConfig uworld = config.uworld();
    UworldConfig.World world = uworld.auth().world();

    assertAll(
        () -> assertTrue(uworld.enabled()),
        () -> assertEquals(19, uworld.transferTimeoutSeconds()),
        () -> assertEquals(301, uworld.auth().timeoutSeconds()),
        () -> assertEquals("new-hub", uworld.auth().targetServer()),
        () -> assertEquals("OVERWORLD", world.dimension()),
        () -> assertEquals(1.5, world.spawnX()),
        () -> assertEquals(101.0, world.spawnY()),
        () -> assertEquals(2.5, world.spawnZ()),
        () -> assertEquals(10.0f, world.spawnYaw()),
        () -> assertEquals(20.0f, world.spawnPitch()),
        () -> assertEquals("SURVIVAL", world.gameMode()),
        () -> assertEquals("VOID", world.loaderType()),
        () -> assertEquals("auth_world.schem", world.fileName()),
        () -> assertEquals(1, world.offsetX()),
        () -> assertEquals(2, world.offsetY()),
        () -> assertEquals(3, world.offsetZ()),
        () -> assertEquals(5, world.viewDistance()),
        () -> assertEquals(6, world.simulationDistance()),
        () -> assertEquals(7, world.platformRadius()),
        () -> assertFalse(uworld.diagnostics().enabled()),
        () -> assertEquals(121, uworld.diagnostics().timeoutSeconds()),
        () -> assertEquals(8, uworld.diagnostics().platformRadius()),
        () -> assertTrue(config.isModuleEnabled("starx.uworld")),
        () -> assertEquals(1, warnings.size()),
        () -> assertTrue(warnings.getFirst().startsWith(SCHEMA_WARNING_PREFIX)));
  }

  @Test
  void legacyRootMapsEveryAuthenticationWorldField() throws Exception {
    List<String> warnings = new ArrayList<>();

    StarxConfig config = load(LEGACY_CONFIG, warnings);
    UworldConfig uworld = config.uworld();
    UworldConfig.World world = uworld.auth().world();

    assertAll(
        () -> assertTrue(uworld.enabled()),
        () -> assertEquals(15, uworld.transferTimeoutSeconds()),
        () -> assertEquals(302, uworld.auth().timeoutSeconds()),
        () -> assertEquals("legacy-hub", uworld.auth().targetServer()),
        () -> assertEquals("NETHER", world.dimension()),
        () -> assertEquals(0.5, world.spawnX()),
        () -> assertEquals(100.0, world.spawnY()),
        () -> assertEquals(0.75, world.spawnZ()),
        () -> assertEquals(11.0f, world.spawnYaw()),
        () -> assertEquals(12.0f, world.spawnPitch()),
        () -> assertEquals("ADVENTURE", world.gameMode()),
        () -> assertEquals("SCHEMATIC", world.loaderType()),
        () -> assertEquals("legacy_auth.schem", world.fileName()),
        () -> assertEquals(4, world.offsetX()),
        () -> assertEquals(5, world.offsetY()),
        () -> assertEquals(6, world.offsetZ()),
        () -> assertEquals(7, world.viewDistance()),
        () -> assertEquals(8, world.simulationDistance()),
        () -> assertEquals(9, world.platformRadius()),
        () -> assertFalse(uworld.diagnostics().enabled()),
        () -> assertEquals(120, uworld.diagnostics().timeoutSeconds()),
        () -> assertEquals(5, uworld.diagnostics().platformRadius()),
        () -> assertTrue(config.isModuleEnabled("starx.uworld")),
        () -> assertEquals(2, warnings.size()),
        () -> assertTrue(warnings.getFirst().startsWith(SCHEMA_WARNING_PREFIX)),
        () -> assertEquals(LEGACY_WARNING, warnings.get(1)));
  }

  @Test
  void legacyRootMigrationPersistsAcrossReloads() throws Exception {
    Path file = this.tempDir.resolve("legacy-persisted.yml");
    Files.writeString(file, LEGACY_CONFIG, StandardCharsets.UTF_8);

    List<String> firstWarnings = new ArrayList<>();
    StarxConfig first = ConfigLoader.load(file, firstWarnings::add);
    Map<String, Object> migratedRoot =
        mapping(new Yaml().load(Files.readString(file, StandardCharsets.UTF_8)));
    Map<String, Object> migratedModules = mapping(migratedRoot.get("modules"));

    assertAll(
        () -> assertEquals("legacy-hub", first.uworld().auth().targetServer()),
        () -> assertFalse(migratedRoot.containsKey("limbo")),
        () -> assertTrue(migratedRoot.containsKey("uworld")),
        () -> assertFalse(migratedModules.containsKey("starx.limbo")),
        () -> assertTrue(migratedModules.containsKey("starx.uworld")));

    List<String> secondWarnings = new ArrayList<>();
    StarxConfig second = ConfigLoader.load(file, secondWarnings::add);

    assertAll(
        () -> assertEquals("legacy-hub", second.uworld().auth().targetServer()),
        () -> assertTrue(second.isModuleEnabled("starx.uworld")),
        () -> assertTrue(secondWarnings.isEmpty()));
  }

  @Test
  void disabledLegacyModuleRemainsDisabledAfterMigrationAndReload() throws Exception {
    String yaml = LEGACY_CONFIG.replace(
        "starx.limbo:\n    enabled: true",
        "starx.limbo:\n    enabled: false");
    Path file = this.tempDir.resolve("legacy-disabled.yml");
    Files.writeString(file, yaml, StandardCharsets.UTF_8);

    StarxConfig first = ConfigLoader.load(file);
    StarxConfig second = ConfigLoader.load(file);

    assertFalse(first.isModuleEnabled("starx.uworld"));
    assertFalse(second.isModuleEnabled("starx.uworld"));
  }

  @Test
  void newRootWinsAndEmitsOneMigrationWarning() throws Exception {
    List<String> warnings = new ArrayList<>();

    StarxConfig config = load(BOTH_CONFIG, warnings);

    assertEquals("new-hub", config.uworld().auth().targetServer());
    assertEquals(2, warnings.size());
    assertTrue(warnings.getFirst().startsWith(SCHEMA_WARNING_PREFIX));
    assertEquals(BOTH_ROOTS_WARNING, warnings.get(1));
  }

  @Test
  void blankTargetNormalizesToLobby() throws Exception {
    String yaml = NEW_CONFIG.replace("new-hub", "   ");

    assertEquals("lobby", load(yaml, new ArrayList<>()).uworld().auth().targetServer());
  }

  @Test
  void rejectsNonBooleanUworldFlagsWithTheirFullKeys() {
    assertAll(
        () -> assertInvalidConfig("""
            modules:
              starx.uworld:
                enabled: "true"
            """, "modules.starx.uworld.enabled"),
        () -> assertInvalidConfig("""
            uworld:
              enabled: "true"
            """, "uworld.enabled"),
        () -> assertInvalidConfig("""
            uworld:
              diagnostics:
                enabled: 1
            """, "uworld.diagnostics.enabled"));
  }

  @Test
  void rejectsFractionalUworldIntegersWithTheirFullKeys() {
    assertAll(
        () -> assertInvalidConfig("""
            uworld:
              transfer-timeout-seconds: 15.5
            """, "uworld.transfer-timeout-seconds"),
        () -> assertInvalidConfig("""
            uworld:
              auth:
                world:
                  offset-x: 1.5
            """, "uworld.auth.world.offset-x"));
  }

  @Test
  void missingDatabaseRootUsesTheBundledSqliteDefaults() throws Exception {
    var database = load(NEW_CONFIG, new ArrayList<>()).database();

    assertAll(
        () -> assertEquals("sqlite", database.type()),
        () -> assertEquals("plugins/starx/data.db", database.database()),
        () -> assertEquals(2, database.poolMaxSize()));
  }

  @Test
  void moduleAliasUsesNewIdBeforeLegacyId() throws Exception {
    String yaml = """
        modules:
          starx.uworld:
            enabled: false
          starx.limbo:
            enabled: true
        """;

    assertFalse(load(yaml, new ArrayList<>()).isModuleEnabled("starx.uworld"));
  }

  @Test
  void moduleAliasReadsLegacyIdWhenNewIdIsAbsent() throws Exception {
    String yaml = """
        modules:
          starx.limbo:
            enabled: true
        """;

    assertTrue(load(yaml, new ArrayList<>()).isModuleEnabled("starx.uworld"));
  }

  @Test
  void hubDoesNotFallBackToLegacyLimboModule() throws Exception {
    String yaml = """
        modules:
          starx.hub:
            enabled: false
          starx.limbo:
            enabled: true
        """;

    assertFalse(load(yaml, new ArrayList<>()).isModuleEnabled("starx.hub"));
  }

  @Test
  void missingFileIsGeneratedFromCompleteUworldResource() throws Exception {
    Path file = this.tempDir.resolve("nested").resolve("config.yml");

    StarxConfig config = ConfigLoader.load(file);
    String generated = Files.readString(file, StandardCharsets.UTF_8);
    UworldConfig defaults = config.uworld();
    Map<String, Object> root = mapping(new Yaml().load(generated));
    Map<String, Object> modules = mapping(root.get("modules"));
    Map<String, Object> uworld = mapping(root.get("uworld"));
    Map<String, Object> auth = mapping(uworld.get("auth"));
    Map<String, Object> world = mapping(auth.get("world"));
    Map<String, Object> diagnostics = mapping(uworld.get("diagnostics"));

    assertAll(
        () -> assertTrue(defaults.enabled()),
        () -> assertEquals(30, defaults.transferTimeoutSeconds()),
        () -> assertEquals(300, defaults.auth().timeoutSeconds()),
        () -> assertEquals("lobby", defaults.auth().targetServer()),
        () -> assertEquals("OVERWORLD", defaults.auth().world().dimension()),
        () -> assertEquals("ADVENTURE", defaults.auth().world().gameMode()),
        () -> assertEquals("AUTO", defaults.auth().world().loaderType()),
        () -> assertEquals("auth_world.schem", defaults.auth().world().fileName()),
        () -> assertEquals(4, defaults.auth().world().viewDistance()),
        () -> assertEquals(4, defaults.auth().world().simulationDistance()),
        () -> assertEquals(5, defaults.auth().world().platformRadius()),
        () -> assertFalse(defaults.diagnostics().enabled()),
        () -> assertEquals(120, defaults.diagnostics().timeoutSeconds()),
        () -> assertEquals(5, defaults.diagnostics().platformRadius()),
        () -> assertEquals(
            Set.of(
                "schema-version", "auto-config", "api-key", "compatibility", "http", "network-automation", "webhook", "website-sync", "database",
                "uniauth", "auth", "player-list", "modules", "napcat", "totp", "uworld"),
            root.keySet()),
        () -> assertEquals(
            Set.of(
                "starx.auth", "starx.auth.yggdrasil", "starx.auth.uniauth",
                "starx.player-list", "starx.player-sessions", "starx.auth.migration",
                "starx.auth.migration.commands",
                "starx.skin-bridge", "starx.chat", "starx.maintenance", "starx.motd",
                "starx.redirect", "starx.queue", "starx.tutorial", "starx.hub", "starx.uworld", "starx.reconnect",
                "starx.info", "starx.forge", "starx.proxytools.raknet", "starx.online",
                "starx.backend-bridge", "starx.messaging", "starx.welcome", "starx.admin",
                "starx.enhanced", "starx.proxytools.filecleaner", "starx.security.bot",
                "starx.security.crash", "starx.security.risk", "starx.security.anticheat",
                "starx.security.blossom", "starx.security.smart-rate",
                "starx.security.smart-alert", "starx.proxytools.smart-queue",
                "starx.integrations.qq", "starx.integrations.plan",
                "starx.integrations.mapmod",
                "starx.integrations.napcat", "starx.integrations.luckperms",
                "starx.integrations.floodgate", "starx.integrations.tab", "starx.vote"),
            modules.keySet()),
        () -> assertEquals(
            Set.of("enabled", "transfer-timeout-seconds", "auth", "diagnostics"),
            uworld.keySet()),
        () -> assertEquals(Set.of("timeout-seconds", "target-server", "world"),
            auth.keySet()),
        () -> assertEquals(
            Set.of(
                "dimension", "spawn-x", "spawn-y", "spawn-z", "spawn-yaw",
                "spawn-pitch", "game-mode", "loader-type", "file-name", "offset-x",
                "offset-y", "offset-z", "view-distance", "simulation-distance",
                "platform-radius", "void-rescue-threshold"),
            world.keySet()),
        () -> assertEquals(Set.of("enabled", "timeout-seconds", "platform-radius"),
            diagnostics.keySet()),
        () -> assertEquals(1, count(generated, "(?m)^uworld:$")),
        () -> assertEquals(1, count(generated, "(?m)^  starx\\.uworld:$")),
        () -> assertEquals(0, count(generated, "(?m)^limbo:$")),
        () -> assertEquals(0, count(generated, "(?m)^  starx\\.limbo:$")));
  }

  private StarxConfig load(String yaml, List<String> warnings) throws IOException {
    Path file = this.tempDir.resolve("config.yml");
    Files.writeString(file, yaml, StandardCharsets.UTF_8);
    return ConfigLoader.load(file, warnings::add);
  }

  private void assertInvalidConfig(String yaml, String fullKey) {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> load(yaml, new ArrayList<>()));
    assertTrue(
        error.getMessage().contains(fullKey),
        () -> "Expected error to contain " + fullKey + ", but was: " + error.getMessage());
  }

  private static long count(String text, String regex) {
    return java.util.regex.Pattern.compile(regex).matcher(text).results().count();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapping(Object value) {
    return (Map<String, Object>) value;
  }
}
