package io.github.addxiaoyi.starx.velocity.config;

import io.github.addxiaoyi.starx.website.SecretValue;
import io.github.addxiaoyi.starx.website.WebsitePlatform;
import io.github.addxiaoyi.starx.website.WebsiteSyncConfig;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

final class VelocityWebsiteSyncConfigParser {
  private VelocityWebsiteSyncConfigParser() {
  }

  static WebsiteSyncConfig parse(Map<String, Object> node) {
    Map<String, Object> heartbeat = child(node, "heartbeat");
    Map<String, Object> textures = child(node, "textures");
    WebsitePlatform platform = WebsitePlatform.parse(string(node, "platform", "velocity"));
    if (platform != WebsitePlatform.VELOCITY) {
      throw new IllegalArgumentException(
          "Velocity website-sync.platform must be velocity, actual=" + platform.wireName());
    }
    return new WebsiteSyncConfig(
        bool(node, "enabled", false),
        URI.create(string(node, "site-url", "https://star-web.top")),
        string(node, "node-id", "proxy-1"),
        platform,
        SecretValue.of(string(node, "bootstrap-token", "")),
        SecretValue.of(string(node, "node-token", "")),
        new WebsiteSyncConfig.Heartbeat(
            integer(heartbeat, "interval-seconds", 15),
            integer(heartbeat, "connect-timeout-ms", 3_000),
            integer(heartbeat, "request-timeout-ms", 8_000)),
        new WebsiteSyncConfig.Textures(
            bool(textures, "enabled", true),
            string(textures, "source", "skinsrestorer"),
            integer(textures, "manifest-interval-seconds", 300),
            integer(textures, "batch-size", 500)));
  }

  private static Map<String, Object> child(Map<String, Object> node, String key) {
    Object value = node.get(key);
    if (!(value instanceof Map<?, ?> map)) {
      return Map.of();
    }
    java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
    map.forEach((entryKey, entryValue) -> result.put(String.valueOf(entryKey), entryValue));
    return result;
  }

  private static String string(Map<String, Object> node, String key, String fallback) {
    Object value = node.get(key);
    return value == null ? fallback : Objects.toString(value, fallback).trim();
  }

  private static boolean bool(Map<String, Object> node, String key, boolean fallback) {
    Object value = node.get(key);
    if (value instanceof Boolean flag) {
      return flag;
    }
    if (value instanceof String text) {
      return Boolean.parseBoolean(text.trim());
    }
    return fallback;
  }

  private static int integer(Map<String, Object> node, String key, int fallback) {
    Object value = node.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }
}
