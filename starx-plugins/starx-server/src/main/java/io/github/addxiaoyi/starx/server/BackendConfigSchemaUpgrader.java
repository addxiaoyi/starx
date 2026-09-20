package io.github.addxiaoyi.starx.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class BackendConfigSchemaUpgrader {
  static final int CURRENT_SCHEMA_VERSION = 1;
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private BackendConfigSchemaUpgrader() {
  }

  static UpgradeResult upgrade(JavaPlugin plugin, boolean firstBoot) throws IOException {
    Objects.requireNonNull(plugin, "plugin");
    Path config = plugin.getDataFolder().toPath().resolve("config.yml");
    try (InputStream input = Objects.requireNonNull(
        plugin.getResource("config.yml"), "embedded config.yml");
         Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      String defaults = readAll(reader);
      UpgradeResult result = upgrade(
          config, defaults, Clock.systemUTC(), firstBoot, plugin.getLogger()::info);
      if (result.changed()) {
        plugin.reloadConfig();
      }
      return result;
    }
  }

  static UpgradeResult upgrade(
      Path config,
      String defaultsYaml,
      Clock clock,
      boolean firstBoot,
      Consumer<String> log
  ) throws IOException {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(defaultsYaml, "defaultsYaml");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(log, "log");

    YamlConfiguration defaults = new YamlConfiguration();
    try {
      defaults.loadFromString(defaultsYaml);
    } catch (InvalidConfigurationException error) {
      throw new IllegalArgumentException("Embedded backend config is invalid", error);
    }

    YamlConfiguration current = new YamlConfiguration();
    if (Files.isRegularFile(config)) {
      try {
        current.load(config.toFile());
      } catch (InvalidConfigurationException error) {
        throw new IllegalStateException("Backend config is invalid: " + config, error);
      }
    }
    Object schemaValue = current.get("schema-version");
    if (schemaValue != null && !(schemaValue instanceof Number)) {
      throw new IllegalStateException("Backend config schema-version must be an integer");
    }
    int sourceVersion = current.getInt("schema-version", 0);
    if (sourceVersion < 0) {
      throw new IllegalStateException("Backend config schema-version must not be negative");
    }
    if (sourceVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalStateException(
          "Backend config schema " + sourceVersion + " is newer than supported schema "
              + CURRENT_SCHEMA_VERSION);
    }

    List<String> addedPaths = new ArrayList<>();
    mergeMissing(current, defaults, "", addedPaths);
    if (current.getInt("schema-version", 0) != CURRENT_SCHEMA_VERSION) {
      current.set("schema-version", CURRENT_SCHEMA_VERSION);
    }
    boolean changed = sourceVersion != CURRENT_SCHEMA_VERSION || !addedPaths.isEmpty();
    if (!changed) {
      return new UpgradeResult(sourceVersion, CURRENT_SCHEMA_VERSION, false, null, null,
          List.of());
    }

    Files.createDirectories(config.toAbsolutePath().normalize().getParent());
    String stamp = STAMP.format(Instant.now(clock));
    Path backup = null;
    if (!firstBoot && Files.isRegularFile(config)) {
      backup = uniqueSibling(config,
          config.getFileName() + ".backup-v" + sourceVersion + "-" + stamp + ".yml");
      Files.copy(config, backup, StandardCopyOption.COPY_ATTRIBUTES);
    }

    writeAtomically(config, current.saveToString());
    Path report = uniqueSibling(config,
        config.getFileName() + ".migration-v" + sourceVersion + "-to-v"
            + CURRENT_SCHEMA_VERSION + "-" + stamp + ".json");
    writeAtomically(report, reportJson(config, backup, sourceVersion, addedPaths));
    log.accept("StarX backend configuration upgraded from schema " + sourceVersion
        + " to " + CURRENT_SCHEMA_VERSION
        + (backup == null ? "" : "; backup=" + backup.getFileName())
        + "; report=" + report.getFileName());
    return new UpgradeResult(sourceVersion, CURRENT_SCHEMA_VERSION, true, backup, report,
        List.copyOf(addedPaths));
  }

  private static void mergeMissing(
      ConfigurationSection target,
      ConfigurationSection defaults,
      String prefix,
      List<String> addedPaths
  ) {
    for (String key : defaults.getKeys(false)) {
      String path = prefix.isEmpty() ? key : prefix + "." + key;
      Object defaultValue = defaults.get(key);
      if (defaultValue instanceof ConfigurationSection defaultSection) {
        ConfigurationSection targetSection;
        if (target.contains(key)) {
          targetSection = target.getConfigurationSection(key);
          if (targetSection == null) {
            throw new IllegalStateException(
                "Backend config path must be a section: " + path);
          }
        } else {
          targetSection = target.createSection(key);
        }
        mergeMissing(targetSection, defaultSection, path, addedPaths);
      } else if (target.getConfigurationSection(key) != null) {
        throw new IllegalStateException(
            "Backend config path must be a scalar value: " + path);
      } else if (!target.contains(key)) {
        target.set(key, defaultValue);
        if (!"schema-version".equals(path)) {
          addedPaths.add(path);
        }
      }
    }
  }

  private static Path uniqueSibling(Path path, String fileName) {
    Path parent = path.toAbsolutePath().normalize().getParent();
    Path candidate = parent.resolve(fileName);
    int suffix = 1;
    while (Files.exists(candidate)) {
      candidate = parent.resolve(fileName + "." + suffix++);
    }
    return candidate;
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Path absolute = target.toAbsolutePath().normalize();
    Path parent = Objects.requireNonNull(absolute.getParent(), "config parent");
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      try {
        Files.move(temporary, absolute,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static String reportJson(
      Path config,
      Path backup,
      int sourceVersion,
      List<String> addedPaths
  ) {
    StringBuilder json = new StringBuilder(256)
        .append("{\n")
        .append("  \"config\": ").append(quote(config.toAbsolutePath().toString())).append(",\n")
        .append("  \"backup\": ")
        .append(backup == null ? "null" : quote(backup.toAbsolutePath().toString())).append(",\n")
        .append("  \"fromSchema\": ").append(sourceVersion).append(",\n")
        .append("  \"toSchema\": ").append(CURRENT_SCHEMA_VERSION).append(",\n")
        .append("  \"addedPaths\": [");
    for (int index = 0; index < addedPaths.size(); index++) {
      if (index > 0) {
        json.append(", ");
      }
      json.append(quote(addedPaths.get(index)));
    }
    return json.append("]\n}\n").toString();
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String readAll(Reader reader) throws IOException {
    StringBuilder content = new StringBuilder();
    char[] buffer = new char[4096];
    int read;
    while ((read = reader.read(buffer)) != -1) {
      content.append(buffer, 0, read);
    }
    return content.toString();
  }

  record UpgradeResult(
      int sourceVersion,
      int targetVersion,
      boolean changed,
      Path backup,
      Path report,
      List<String> addedPaths
  ) {
  }
}
