/*
 * Copyright (C) 2025 StarX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.github.addxiaoyi.starx.velocity.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

final class ConfigSchemaUpgrader {

  static final int CURRENT_SCHEMA_VERSION = 5;
  private static final DateTimeFormatter STAMP = DateTimeFormatter
      .ofPattern("yyyyMMdd-HHmmss-SSS")
      .withZone(ZoneOffset.UTC);

  private ConfigSchemaUpgrader() {
  }

  static UpgradeResult upgrade(
      Path path,
      Map<String, Object> current,
      Map<String, Object> defaults,
      Consumer<String> warningSink
  ) throws IOException {
    return upgrade(path, current, defaults, warningSink, Clock.systemUTC(), true);
  }

  static UpgradeResult normalize(
      Path path,
      Map<String, Object> current,
      Map<String, Object> defaults,
      Consumer<String> warningSink
  ) throws IOException {
    return upgrade(path, current, defaults, warningSink, Clock.systemUTC(), false);
  }

  static UpgradeResult upgrade(
      Path path,
      Map<String, Object> current,
      Map<String, Object> defaults,
      Consumer<String> warningSink,
      Clock clock
  ) throws IOException {
    return upgrade(path, current, defaults, warningSink, clock, true);
  }

  private static UpgradeResult upgrade(
      Path path,
      Map<String, Object> current,
      Map<String, Object> defaults,
      Consumer<String> warningSink,
      Clock clock,
      boolean persist
  ) throws IOException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(defaults, "defaults");
    Objects.requireNonNull(warningSink, "warningSink");
    Objects.requireNonNull(clock, "clock");

    int sourceVersion = schemaVersion(current);
    if (sourceVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Configuration schema " + sourceVersion + " is newer than supported schema "
              + CURRENT_SCHEMA_VERSION);
    }

    List<String> addedPaths = new ArrayList<>();
    LegacyMigration legacyMigration = migrateLegacyAliases(current, addedPaths);
    Map<String, Object> merged = merge(defaults, legacyMigration.root(), "", addedPaths);
    merged.remove("schema-version");
    LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
    normalized.put("schema-version", CURRENT_SCHEMA_VERSION);
    normalized.putAll(merged);

    boolean changed = sourceVersion != CURRENT_SCHEMA_VERSION
        || legacyMigration.changed()
        || !addedPaths.isEmpty();
    if (!changed) {
      return new UpgradeResult(sourceVersion, CURRENT_SCHEMA_VERSION, false, null, null,
          List.of(), Map.copyOf(normalized));
    }

    if (!persist) {
      return new UpgradeResult(
          sourceVersion,
          CURRENT_SCHEMA_VERSION,
          true,
          null,
          null,
          List.copyOf(addedPaths),
          Map.copyOf(normalized));
    }

    String stamp = STAMP.format(Instant.now(clock));
    Path backup = uniqueSibling(path,
        path.getFileName() + ".backup-v" + sourceVersion + "-" + stamp + ".yml");
    Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);

    writeAtomically(path, dump(normalized));

    Path report = uniqueSibling(path,
        path.getFileName() + ".migration-v" + sourceVersion + "-to-v"
            + CURRENT_SCHEMA_VERSION + "-" + stamp + ".json");
    Files.writeString(
        report,
        reportJson(path, backup, sourceVersion, addedPaths),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);

    warningSink.accept(
        "StarX configuration upgraded from schema " + sourceVersion + " to "
            + CURRENT_SCHEMA_VERSION + "; backup=" + backup.getFileName()
            + "; report=" + report.getFileName());
    return new UpgradeResult(
        sourceVersion,
        CURRENT_SCHEMA_VERSION,
        true,
        backup,
        report,
        List.copyOf(addedPaths),
        Map.copyOf(normalized));
  }

  private static int schemaVersion(Map<String, Object> root) {
    Object value = root.get("schema-version");
    if (value == null) {
      return 0;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("schema-version must be an integer", error);
    }
  }

  private static LegacyMigration migrateLegacyAliases(
      Map<String, Object> current,
      List<String> migratedPaths
  ) {
    LinkedHashMap<String, Object> migrated = new LinkedHashMap<>();
    current.forEach((key, value) -> migrated.put(key, deepCopy(value)));
    boolean changed = false;

    if (migrated.containsKey("limbo")) {
      Object legacyValue = migrated.remove("limbo");
      if (!(legacyValue instanceof Map<?, ?> legacyMap)) {
        throw new IllegalArgumentException("limbo must be a mapping");
      }
      if (!migrated.containsKey("uworld")) {
        migrated.put("uworld", migrateLegacyUworld(stringMap(legacyMap)));
        migratedPaths.add("uworld");
      }
      changed = true;
    }

    Object modulesValue = migrated.get("modules");
    if (modulesValue instanceof Map<?, ?> modulesMap) {
      LinkedHashMap<String, Object> modules = new LinkedHashMap<>(stringMap(modulesMap));
      if (modules.containsKey("starx.limbo")) {
        Object legacyModule = modules.remove("starx.limbo");
        if (!modules.containsKey("starx.uworld")) {
          modules.put("starx.uworld", deepCopy(legacyModule));
          migratedPaths.add("modules.starx.uworld");
        }
        migrated.put("modules", modules);
        changed = true;
      }
    }

    return new LegacyMigration(Map.copyOf(migrated), changed);
  }

  private static Map<String, Object> migrateLegacyUworld(Map<String, Object> legacy) {
    LinkedHashMap<String, Object> world = new LinkedHashMap<>();
    copyIfPresent(legacy, world, "dimension", "dimension");
    copyIfPresent(legacy, world, "spawn-x", "spawn-x");
    copyIfPresent(legacy, world, "spawn-y", "spawn-y");
    copyIfPresent(legacy, world, "spawn-z", "spawn-z");
    copyIfPresent(legacy, world, "spawn-yaw", "spawn-yaw");
    copyIfPresent(legacy, world, "spawn-pitch", "spawn-pitch");
    copyIfPresent(legacy, world, "game-mode", "game-mode");
    copyIfPresent(legacy, world, "world-loader-type", "loader-type");
    copyIfPresent(legacy, world, "world-file-name", "file-name");
    copyIfPresent(legacy, world, "world-offset-x", "offset-x");
    copyIfPresent(legacy, world, "world-offset-y", "offset-y");
    copyIfPresent(legacy, world, "world-offset-z", "offset-z");
    copyIfPresent(legacy, world, "view-distance", "view-distance");
    copyIfPresent(legacy, world, "simulation-distance", "simulation-distance");
    if (legacy.containsKey("platform-radius")) {
      world.put("platform-radius", deepCopy(legacy.get("platform-radius")));
    } else {
      copyIfPresent(legacy, world, "platform-size", "platform-radius");
    }

    LinkedHashMap<String, Object> auth = new LinkedHashMap<>();
    copyIfPresent(legacy, auth, "auth-timeout-seconds", "timeout-seconds");
    copyIfPresent(legacy, auth, "hub-server", "target-server");
    auth.put("world", world);

    LinkedHashMap<String, Object> uworld = new LinkedHashMap<>();
    copyIfPresent(legacy, uworld, "enabled", "enabled");
    uworld.put("transfer-timeout-seconds", 15);
    uworld.put("auth", auth);
    return uworld;
  }

  private static void copyIfPresent(
      Map<String, Object> source,
      Map<String, Object> target,
      String sourceKey,
      String targetKey
  ) {
    if (source.containsKey(sourceKey)) {
      target.put(targetKey, deepCopy(source.get(sourceKey)));
    }
  }

  private static Map<String, Object> merge(
      Map<String, Object> defaults,
      Map<String, Object> current,
      String prefix,
      List<String> addedPaths
  ) {
    LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : defaults.entrySet()) {
      String key = entry.getKey();
      String path = prefix.isEmpty() ? key : prefix + "." + key;
      if (!current.containsKey(key)) {
        merged.put(key, deepCopy(entry.getValue()));
        if (!"schema-version".equals(path)) {
          addedPaths.add(path);
        }
        continue;
      }
      Object defaultValue = entry.getValue();
      Object currentValue = current.get(key);
      if (defaultValue instanceof Map<?, ?> defaultMap
          && currentValue instanceof Map<?, ?> currentMap) {
        merged.put(key, merge(stringMap(defaultMap), stringMap(currentMap), path, addedPaths));
      } else {
        merged.put(key, deepCopy(currentValue));
      }
    }
    for (Map.Entry<String, Object> entry : current.entrySet()) {
      if (!merged.containsKey(entry.getKey())) {
        merged.put(entry.getKey(), deepCopy(entry.getValue()));
      }
    }
    return merged;
  }

  private static Object deepCopy(Object value) {
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
      stringMap(map).forEach((key, item) -> copy.put(key, deepCopy(item)));
      return copy;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(ConfigSchemaUpgrader::deepCopy).toList();
    }
    return value;
  }

  private static Map<String, Object> stringMap(Map<?, ?> source) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private static String dump(Map<String, Object> root) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    options.setIndent(2);
    options.setIndicatorIndent(0);
    options.setSplitLines(false);
    return "# StarX configuration; automatically normalized after schema migration.\n"
        + new Yaml(options).dump(root);
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Path parent = target.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new IOException("Configuration path has no parent: " + target);
    }
    Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
    boolean moved = false;
    try {
      Files.writeString(
          temp,
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      try {
        Files.move(
            temp,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temp);
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

  private static String reportJson(
      Path config,
      Path backup,
      int sourceVersion,
      List<String> addedPaths
  ) {
    StringBuilder json = new StringBuilder();
    json.append("{\n")
        .append("  \"config\": \"").append(escape(config.toAbsolutePath().toString())).append("\",\n")
        .append("  \"backup\": \"").append(escape(backup.toAbsolutePath().toString())).append("\",\n")
        .append("  \"fromSchema\": ").append(sourceVersion).append(",\n")
        .append("  \"toSchema\": ").append(CURRENT_SCHEMA_VERSION).append(",\n")
        .append("  \"addedPaths\": [");
    for (int index = 0; index < addedPaths.size(); index++) {
      if (index > 0) {
        json.append(", ");
      }
      json.append("\"").append(escape(addedPaths.get(index))).append("\"");
    }
    return json.append("]\n}\n").toString();
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private record LegacyMigration(Map<String, Object> root, boolean changed) {
  }

  record UpgradeResult(
      int sourceVersion,
      int targetVersion,
      boolean changed,
      Path backup,
      Path report,
      List<String> addedPaths,
      Map<String, Object> root
  ) {
  }
}
