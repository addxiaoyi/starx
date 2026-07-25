package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

final class ConfigLoaderAuthUxTest {

  @TempDir
  Path tempDir;

  @Test
  void missingUxSectionUsesModernChineseDefaults() throws Exception {
    StarxConfig.AuthUxConfig ux = load("auth:\n  allow-offline-default: false\n").auth().ux();

    assertAll(
        () -> assertTrue(ux.titlesEnabled()),
        () -> assertTrue(ux.actionBarEnabled()),
        () -> assertTrue(ux.soundsEnabled()),
        () -> assertEquals("minecraft:block.note_block.chime", ux.promptSound()),
        () -> assertEquals("minecraft:entity.player.levelup", ux.successSound()),
        () -> assertEquals("minecraft:block.note_block.bass", ux.errorSound()),
        () -> assertEquals("欢迎回来", ux.messages().loginTitle()),
        () -> assertEquals("欢迎来到 StarMC", ux.messages().registerTitle()));
  }

  @Test
  void everyPlayerFacingUxSettingCanBeOverridden() throws Exception {
    StarxConfig.AuthUxConfig ux = load("""
        auth:
          allow-offline-default: false
          ux:
            titles-enabled: false
            action-bar-enabled: false
            sounds-enabled: false
            prompt-sound: "minecraft:block.amethyst_block.chime"
            success-sound: "minecraft:ui.toast.challenge_complete"
            error-sound: "minecraft:entity.villager.no"
            messages:
              login-title: "自定义登录"
              login-subtitle: "自定义副标题"
              register-title: "自定义注册"
              register-subtitle: "自定义注册副标题"
              totp-title: "自定义二步验证"
              totp-subtitle: "自定义验证码提示"
              success-title: "自定义成功"
              success-subtitle: "自定义转服提示"
        """).auth().ux();

    assertAll(
        () -> assertFalse(ux.titlesEnabled()),
        () -> assertFalse(ux.actionBarEnabled()),
        () -> assertFalse(ux.soundsEnabled()),
        () -> assertEquals("minecraft:block.amethyst_block.chime", ux.promptSound()),
        () -> assertEquals("minecraft:ui.toast.challenge_complete", ux.successSound()),
        () -> assertEquals("minecraft:entity.villager.no", ux.errorSound()),
        () -> assertEquals("自定义登录", ux.messages().loginTitle()),
        () -> assertEquals("自定义副标题", ux.messages().loginSubtitle()),
        () -> assertEquals("自定义注册", ux.messages().registerTitle()),
        () -> assertEquals("自定义注册副标题", ux.messages().registerSubtitle()),
        () -> assertEquals("自定义二步验证", ux.messages().totpTitle()),
        () -> assertEquals("自定义验证码提示", ux.messages().totpSubtitle()),
        () -> assertEquals("自定义成功", ux.messages().successTitle()),
        () -> assertEquals("自定义转服提示", ux.messages().successSubtitle()));
  }

  @Test
  void generatedConfigContainsTheCompleteUxTree() throws Exception {
    Path file = this.tempDir.resolve("generated.yml");

    ConfigLoader.load(file);
    Map<String, Object> root = mapping(new Yaml().load(Files.readString(file)));
    Map<String, Object> auth = mapping(root.get("auth"));
    Map<String, Object> ux = mapping(auth.get("ux"));
    Map<String, Object> messages = mapping(ux.get("messages"));

    assertAll(
        () -> assertEquals(false, auth.get("allow-offline-default")),
        () -> assertEquals(
            Set.of(
                "titles-enabled", "action-bar-enabled", "sounds-enabled",
                "prompt-sound", "success-sound", "error-sound", "messages"),
            ux.keySet()),
        () -> assertEquals(
            Set.of(
                "login-title", "login-subtitle", "register-title", "register-subtitle",
                "totp-title", "totp-subtitle", "success-title", "success-subtitle"),
            messages.keySet()));
  }

  private StarxConfig load(String yaml) throws Exception {
    Path file = this.tempDir.resolve("config.yml");
    Files.writeString(file, yaml, StandardCharsets.UTF_8);
    return ConfigLoader.load(file);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapping(Object value) {
    return (Map<String, Object>) value;
  }
}
