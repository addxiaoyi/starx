package io.github.addxiaoyi.starx.velocity.module.tab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Keeps the last valid aliases when an administrator saves an incomplete file. */
final class ServerNameMappings {
  private final Path path;
  private final Consumer<String> warning;
  private volatile Map<String, String> names = Map.of();
  private Fingerprint observed;
  private boolean unreadable;

  ServerNameMappings(Path path, Consumer<String> warning) {
    this.path = Objects.requireNonNull(path, "path");
    this.warning = Objects.requireNonNull(warning, "warning");
    try {
      if (Files.notExists(path)) {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, "# 键为 velocity.toml 中的真实子服名；修改后自动加载。\n"
            + "servers:\n  lobby: \"大厅\"\n  survival: \"生存服\"\n  minigames: \"小游戏\"\n",
            StandardCharsets.UTF_8);
      }
    } catch (IOException error) {
      this.warning.accept("无法创建 cross-server-tab.yml：" + error.getMessage());
    }
    reloadIfChanged();
  }

  String resolve(String serverName) {
    return this.names.getOrDefault(serverName, serverName);
  }

  int size() {
    return this.names.size();
  }

  synchronized boolean reloadIfChanged() {
    Fingerprint current;
    try {
      BasicFileAttributes attributes = Files.readAttributes(this.path, BasicFileAttributes.class);
      current = new Fingerprint(attributes.lastModifiedTime(), attributes.size());
    } catch (IOException error) {
      readFailed(error);
      return false;
    }
    if (!this.unreadable && current.equals(this.observed)) return false;
    try {
      String content = Files.readString(this.path, StandardCharsets.UTF_8);
      this.unreadable = false;
      this.observed = current;
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      Object parsed = new Yaml(new SafeConstructor(options))
          .load(content);
      if (!(parsed instanceof Map<?, ?> root) || !(root.get("servers") instanceof Map<?, ?> values)) {
        throw new IllegalArgumentException("servers 必须是子服名与显示名称的映射；清空映射请使用 servers: {}");
      }
      Map<String, String> aliases = new LinkedHashMap<>();
      for (var entry : values.entrySet()) {
        if (!(entry.getKey() instanceof String key) || key.isBlank()
            || !(entry.getValue() instanceof String value) || value.isBlank()) {
          throw new IllegalArgumentException("子服名与显示名称必须是非空字符串");
        }
        if (aliases.putIfAbsent(key.trim(), value.trim()) != null) {
          throw new IllegalArgumentException("子服名称重复：" + key.trim());
        }
      }
      this.names = Map.copyOf(aliases);
      return true;
    } catch (IOException error) {
      readFailed(error);
      return false;
    } catch (RuntimeException error) {
      this.warning.accept("cross-server-tab.yml 无效，保留上一份有效映射：" + error.getMessage());
      return false;
    }
  }

  private void readFailed(IOException error) {
    if (!this.unreadable) {
      this.warning.accept("无法读取 cross-server-tab.yml，保留上一份有效映射：" + error.getMessage());
    }
    this.unreadable = true;
  }

  private record Fingerprint(FileTime modifiedAt, long size) { }
}
