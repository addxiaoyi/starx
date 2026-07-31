package io.github.addxiaoyi.starx.velocity.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

final class MiniMotdMigration {
  private static final long MAX_CONFIG_BYTES = 1_048_576;
  private static final long MAX_ICON_BYTES = 8 * 1_024 * 1_024;
  private static final String MARKER = ".minimotd-migrated";
  private static final Pattern SAFE_BASENAME = Pattern.compile("[A-Za-z0-9_-]+");

  private MiniMotdMigration() {
  }

  record Result(boolean migrated, boolean iconCopied, Path marker) {
    static Result skipped(Path marker) {
      return new Result(false, false, marker);
    }
  }

  static Result migrate(Path starxDirectory, Map<String, Object> root, Consumer<String> warningSink)
      throws IOException {
    Objects.requireNonNull(starxDirectory, "starxDirectory");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(warningSink, "warningSink");

    Path data = starxDirectory.toAbsolutePath().normalize();
    Files.createDirectories(data);
    Path marker = data.resolve(MARKER);
    if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
      return Result.skipped(marker);
    }

    Path source = findSource(data);
    if (source == null) {
      return Result.skipped(marker);
    }
    Path configFile = source.resolve("main.conf");
    if (!safeRegularFile(configFile) || Files.size(configFile) > MAX_CONFIG_BYTES) {
      warningSink.accept("MiniMOTD migration skipped: main.conf is unsafe or too large");
      return Result.skipped(marker);
    }

    ParsedMiniMotd parsed;
    try {
      parsed = parse(configFile);
    } catch (ConfigException | IllegalArgumentException error) {
      warningSink.accept("MiniMOTD migration skipped: main.conf is malformed: " + error.getMessage());
      return Result.skipped(marker);
    }
    if (parsed.entries().isEmpty()) {
      warningSink.accept("MiniMOTD migration skipped: no valid motds were found");
      return Result.skipped(marker);
    }

    Path icon = null;
    if (parsed.iconEnabled()) {
      icon = chooseIcon(source, parsed.iconName(), warningSink);
      if (parsed.iconName() != null && icon == null) {
        return Result.skipped(marker);
      }
    }

    Map<String, Object> motd = module(root);
    putIfDefault(motd, "normal", parsed.entries().get(0), "欢迎来到 StarX！");
    if (parsed.entries().size() > 1) {
      putIfDefault(motd, "maintenance", parsed.entries().get(1), "StarX 正在维护中。");
    }
    if (parsed.maximumPlayers() != null) {
      putIfDefault(motd, "maximum-players", parsed.maximumPlayers(), 100);
    }

    boolean iconCopied = false;
    if (icon != null && !hasExistingManagedIcon(data, motd)) {
      copyIconAtomically(icon, data.resolve("server-icon.png"));
      putIfDefault(motd, "icon-path", "server-icon.png", "");
      putIfDefault(motd, "managed-icon", "server-icon.png", "");
      iconCopied = true;
    }
    return new Result(true, iconCopied, marker);
  }

  static void markComplete(Result result) throws IOException {
    Objects.requireNonNull(result, "result");
    if (!result.migrated()) {
      return;
    }
    Path marker = result.marker();
    Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
    Files.writeString(temporary, "migrated-from=MiniMOTD\n", StandardCharsets.UTF_8);
    try {
      try {
        Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static ParsedMiniMotd parse(Path configFile) throws IOException {
    Config config = ConfigFactory.parseFile(configFile.toFile()).resolve();
    List<String> entries = new ArrayList<>();
    for (Config entry : config.getConfigList("motds")) {
      String line1 = entry.getString("line1");
      String line2 = entry.hasPath("line2") ? entry.getString("line2") : "";
      entries.add(line2.isEmpty() ? line1 : line1 + "\n" + line2);
    }
    Integer maximumPlayers = null;
    if (config.hasPath("player-count-settings.max-players-enabled")
        && config.getBoolean("player-count-settings.max-players-enabled")
        && config.hasPath("player-count-settings.max-players")) {
      int value = config.getInt("player-count-settings.max-players");
      if (value > 0) {
        maximumPlayers = value;
      }
    }
    boolean iconEnabled = config.hasPath("icon-enabled") && config.getBoolean("icon-enabled");
    String iconName = null;
    if (iconEnabled && !config.getConfigList("motds").isEmpty()) {
      Config first = config.getConfigList("motds").get(0);
      if (first.hasPath("icon")) {
        iconName = first.getString("icon");
      }
    }
    return new ParsedMiniMotd(List.copyOf(entries), maximumPlayers, iconEnabled, iconName);
  }

  private static Path chooseIcon(Path source, String requested, Consumer<String> warningSink)
      throws IOException {
    Path icons = source.resolve("icons");
    if (!safeDirectory(icons)) {
      warningSink.accept("MiniMOTD icon skipped: icons directory is unsafe or missing");
      return null;
    }
    if (requested != null && !requested.isBlank() && !"random".equalsIgnoreCase(requested)) {
      if (!SAFE_BASENAME.matcher(requested).matches()) {
        warningSink.accept("MiniMOTD icon skipped: icon must be a safe basename");
        return null;
      }
      Path candidate = icons.resolve(requested + ".png");
      return validPng(candidate) ? candidate : null;
    }
    try (var stream = Files.list(icons)) {
      List<Path> candidates = stream
          .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".png"))
          .filter(MiniMotdMigration::validPng)
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
      return candidates.isEmpty() ? null : candidates.get(0);
    }
  }

  private static boolean validPng(Path path) {
    try {
      if (!safeRegularFile(path) || Files.size(path) > MAX_ICON_BYTES) {
        return false;
      }
      BufferedImage image = ImageIO.read(path.toFile());
      return image != null && image.getWidth() > 0 && image.getHeight() > 0;
    } catch (IOException | RuntimeException ignored) {
      return false;
    }
  }

  private static void copyIconAtomically(Path source, Path target) throws IOException {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    Files.copy(source, temporary, StandardCopyOption.COPY_ATTRIBUTES);
    try {
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
  private static boolean hasExistingManagedIcon(Path data, Map<String, Object> motd) {
    Object configured = motd.get("icon-path");
    if (configured instanceof String path && !path.isBlank()) {
      Path candidate = data.resolve(path).normalize();
      return candidate.startsWith(data)
          && Files.exists(candidate, LinkOption.NOFOLLOW_LINKS);
    }
    return Files.exists(data.resolve("server-icon.png"), LinkOption.NOFOLLOW_LINKS);
  }

  private static Path findSource(Path data) {
    Path parent = data.getParent();
    if (parent == null) {
      return null;
    }
    for (String name : List.of("minimotd-velocity", "MiniMOTD", "MiniMOTD-velocity")) {
      Path candidate = parent.resolve(name).normalize();
      if (safeDirectory(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private static boolean safeDirectory(Path path) {
    return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
  }

  private static boolean safeRegularFile(Path path) {
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
  }

  private static Map<String, Object> module(Map<String, Object> root) {
    Object modulesNode = root.get("modules");
    if (!(modulesNode instanceof Map<?, ?> modules)) {
      Map<String, Object> created = new LinkedHashMap<>();
      root.put("modules", created);
      modulesNode = created;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> module = (Map<String, Object>) ((Map<?, ?>) modulesNode).get("starx.motd");
    if (module == null) {
      module = new LinkedHashMap<>();
      ((Map<String, Object>) modulesNode).put("starx.motd", module);
    }
    return module;
  }

  private static void putIfDefault(Map<String, Object> target, String key, Object value, Object defaultValue) {
    Object current = target.get(key);
    if (current == null || Objects.equals(current, defaultValue)) {
      target.put(key, value);
    }
  }

  private record ParsedMiniMotd(
      List<String> entries,
      Integer maximumPlayers,
      boolean iconEnabled,
      String iconName) {
  }
}
