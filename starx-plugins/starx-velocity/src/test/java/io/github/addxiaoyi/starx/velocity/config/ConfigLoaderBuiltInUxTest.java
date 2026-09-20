package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigLoaderBuiltInUxTest {

  @TempDir
  Path tempDir;

  @Test
  void builtInIdentityAndPlayerListSettingsAreFullyCustomizable() throws Exception {
    StarxConfig config = load("""
        auth:
          offline-identity:
            prefix: "_"
            display-name: "基岩前缀账号"
        player-list:
          refresh-seconds: 9
          header: "<gold>你好 {starx_player}</gold>"
          footer: "<gray>{starx_server} / {starx_online}</gray>"
        """);

    assertAll(
        () -> assertEquals("_", config.auth().offlineIdentity().prefix()),
        () -> assertEquals("基岩前缀账号", config.auth().offlineIdentity().displayName()),
        () -> assertEquals(9, config.playerList().refreshSeconds()),
        () -> assertEquals("<gold>你好 {starx_player}</gold>", config.playerList().header()),
        () -> assertEquals(
            "<gray>{starx_server} / {starx_online}</gray>", config.playerList().footer()));
  }

  @Test
  void missingBuiltInUxSettingsUseModernChineseDefaults() throws Exception {
    StarxConfig config = load("modules: {}\n");

    assertAll(
        () -> assertEquals(".", config.auth().offlineIdentity().prefix()),
        () -> assertEquals("前缀离线账号", config.auth().offlineIdentity().displayName()),
        () -> assertEquals(5, config.playerList().refreshSeconds()),
        () -> assertTrue(config.playerList().header().contains("StarMC")),
        () -> assertTrue(config.playerList().footer().contains("{starx_server}")));
  }

  @Test
  void renamedBuiltInModulesReadLegacySwitchesOnlyWhenNewSwitchIsAbsent() throws Exception {
    StarxConfig legacy = load("""
        modules:
          starx.auth.floodgate:
            enabled: true
          starx.auth.tab:
            enabled: true
          starx.placeholder:
            enabled: true
        """);
    StarxConfig newWins = load("""
        modules:
          starx.auth.offline-identity:
            enabled: false
          starx.auth.floodgate:
            enabled: true
          starx.player-list:
            enabled: false
          starx.auth.tab:
            enabled: true
          starx.variables:
            enabled: false
          starx.placeholder:
            enabled: true
        """);

    assertAll(
        () -> assertTrue(legacy.isModuleEnabled("starx.auth.offline-identity")),
        () -> assertTrue(legacy.isModuleEnabled("starx.player-list")),
        () -> assertTrue(legacy.isModuleEnabled("starx.variables")),
        () -> assertFalse(newWins.isModuleEnabled("starx.auth.offline-identity")),
        () -> assertFalse(newWins.isModuleEnabled("starx.player-list")),
        () -> assertFalse(newWins.isModuleEnabled("starx.variables")));
  }

  private StarxConfig load(String yaml) throws Exception {
    Path file = this.tempDir.resolve("config.yml");
    Files.writeString(file, yaml, StandardCharsets.UTF_8);
    return ConfigLoader.load(file);
  }
}
