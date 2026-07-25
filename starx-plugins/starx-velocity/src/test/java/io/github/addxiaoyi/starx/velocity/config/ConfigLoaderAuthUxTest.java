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
  void missingUxSectionUsesCompleteChineseDefaults() throws Exception {
    StarxConfig.AuthUxConfig ux = load("auth:\n  allow-offline-default: false\n").auth().ux();

    assertAll(
        () -> assertTrue(ux.titlesEnabled()),
        () -> assertTrue(ux.actionBarEnabled()),
        () -> assertTrue(ux.soundsEnabled()),
        () -> assertEquals("minecraft:block.note_block.chime", ux.promptSound()),
        () -> assertEquals("minecraft:entity.player.levelup", ux.successSound()),
        () -> assertEquals("minecraft:block.note_block.bass", ux.errorSound()),
        () -> assertEquals("欢迎回来", ux.messages().loginTitle()),
        () -> assertEquals("欢迎来到 StarMC", ux.messages().registerTitle()),
        () -> assertEquals("请输入密码完成登录。", ux.messages().loginPrompt()),
        () -> assertEquals("✦ StarMC 安全登录中心 ✦", ux.card().title()),
        () -> assertEquals("玩家：", ux.card().playerPrefix()),
        () -> assertEquals("分钟", ux.card().minuteUnit()));
  }

  @Test
  void everyAuthenticationUxGroupCanBeOverridden() throws Exception {
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
              login-prompt: "登录聊天提示"
              login-action-bar: "登录动作栏"
              register-prompt: "注册聊天提示"
              register-action-bar: "注册动作栏"
              totp-prompt: "二步验证聊天提示"
              totp-action-bar: "二步验证动作栏"
            card:
              title: "自定义安全中心"
              player-prefix: "用户="
              uuid-prefix: "标识="
              account-type-prefix: "类型="
              current-ip-prefix: "本次地址="
              last-ip-prefix: "历史地址="
              last-login-prefix: "历史登录="
              playtime-prefix: "时长="
              registered-at-prefix: "注册于="
              target-prefix: "前往="
              premium-account: "在线账号"
              offline-account: "本地账号"
              first-login-account: "新账号"
              new-player-name: "新用户"
              no-history: "没有记录"
              registration-premium-account: "在线账号待注册"
              registration-offline-account: "本地账号待注册"
              registration-history: "首次注册记录"
              registration-pending-time: "注册后生成时间"
              unknown-value: "未知值"
              target-unavailable: "目标离线"
              login-link-text: "登录绑定按钮"
              login-link-hover: "登录绑定说明"
              registration-link-text: "注册绑定按钮"
              registration-link-hover: "注册绑定说明"
              hour-unit: "时"
              minute-unit: "分"
        """).auth().ux();

    assertAll(
        () -> assertFalse(ux.titlesEnabled()),
        () -> assertFalse(ux.actionBarEnabled()),
        () -> assertFalse(ux.soundsEnabled()),
        () -> assertEquals("minecraft:block.amethyst_block.chime", ux.promptSound()),
        () -> assertEquals("minecraft:ui.toast.challenge_complete", ux.successSound()),
        () -> assertEquals("minecraft:entity.villager.no", ux.errorSound()),
        () -> assertEquals("自定义登录", ux.messages().loginTitle()),
        () -> assertEquals("登录聊天提示", ux.messages().loginPrompt()),
        () -> assertEquals("二步验证动作栏", ux.messages().totpActionBar()),
        () -> assertEquals("自定义安全中心", ux.card().title()),
        () -> assertEquals("用户=", ux.card().playerPrefix()),
        () -> assertEquals("本地账号待注册", ux.card().registrationOfflineAccount()),
        () -> assertEquals("登录绑定按钮", ux.card().loginLinkText()),
        () -> assertEquals("分", ux.card().minuteUnit()));
  }

  @Test
  void generatedConfigContainsTheCompleteUxTree() throws Exception {
    Path file = this.tempDir.resolve("generated.yml");

    ConfigLoader.load(file);
    Map<String, Object> root = mapping(new Yaml().load(Files.readString(file)));
    Map<String, Object> auth = mapping(root.get("auth"));
    Map<String, Object> ux = mapping(auth.get("ux"));
    Map<String, Object> messages = mapping(ux.get("messages"));
    Map<String, Object> card = mapping(ux.get("card"));

    assertAll(
        () -> assertEquals(false, auth.get("allow-offline-default")),
        () -> assertEquals(
            Set.of(
                "titles-enabled", "action-bar-enabled", "sounds-enabled",
                "prompt-sound", "success-sound", "error-sound", "messages", "card"),
            ux.keySet()),
        () -> assertEquals(
            Set.of(
                "login-title", "login-subtitle", "register-title", "register-subtitle",
                "totp-title", "totp-subtitle", "success-title", "success-subtitle",
                "login-prompt", "login-action-bar", "register-prompt",
                "register-action-bar", "totp-prompt", "totp-action-bar"),
            messages.keySet()),
        () -> assertEquals(
            Set.of(
                "title", "player-prefix", "uuid-prefix", "account-type-prefix",
                "current-ip-prefix", "last-ip-prefix", "last-login-prefix",
                "playtime-prefix", "registered-at-prefix", "target-prefix",
                "premium-account", "offline-account", "first-login-account",
                "new-player-name", "no-history", "registration-premium-account",
                "registration-offline-account", "registration-history",
                "registration-pending-time", "unknown-value", "target-unavailable",
                "login-link-text", "login-link-hover", "registration-link-text",
                "registration-link-hover", "hour-unit", "minute-unit"),
            card.keySet()));
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
