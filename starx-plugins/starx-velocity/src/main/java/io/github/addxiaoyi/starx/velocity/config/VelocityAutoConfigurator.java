package io.github.addxiaoyi.starx.velocity.config;

import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

/**
 * Detects the local Velocity topology and safely fills managed configuration values.
 * Explicit values are preserved unless the corresponding auto-config switch is enabled.
 */
public final class VelocityAutoConfigurator {
  private static final Pattern YAML_KEY =
      Pattern.compile("^(\\s*)([^#][^:]*):(?:\\s*(.*))?$");
  private static final SecureRandom RANDOM = new SecureRandom();

  private VelocityAutoConfigurator() {
  }

  public static Result apply(
      Path configFile,
      ProxyServer proxy,
      boolean firstBoot,
      Consumer<String> logger
  ) throws IOException {
    Objects.requireNonNull(proxy, "proxy");
    Set<String> pluginIds = proxy.getPluginManager().getPlugins().stream()
        .map(container -> container.getDescription().getId().toLowerCase(Locale.ROOT))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> servers = proxy.getAllServers().stream()
        .map(server -> server.getServerInfo().getName())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return apply(configFile, pluginIds, servers, localHostName(), firstBoot, logger);
  }

  static Result apply(
      Path configFile,
      Set<String> pluginIds,
      Set<String> serverNames,
      String hostName,
      boolean firstBoot,
      Consumer<String> logger
  ) throws IOException {
    Objects.requireNonNull(configFile, "configFile");
    Consumer<String> sink = logger == null ? ignored -> { } : logger;
    String source = Files.readString(configFile, StandardCharsets.UTF_8);
    Map<String, Object> root = rootMap(new Yaml().load(source));
    Map<String, Object> auto = child(root, "auto-config");
    if (!bool(auto, "enabled", true)) {
      return writeReport(configFile, auto, pluginIds, serverNames, null, List.of(), firstBoot, sink);
    }

    YamlTextEditor editor = new YamlTextEditor(source);
    List<String> changed = new ArrayList<>();

    if (bool(auto, "generate-api-key", true)
        && string(root, "api-key", "").isBlank()) {
      if (editor.setScalar(List.of("api-key"), quote(randomSecret()))) {
        changed.add("api-key");
      }
    }

    if (bool(auto, "manage-optional-integrations", true)) {
      manageBoolean(
          editor, changed,
          List.of("modules", "starx.integrations.luckperms", "enabled"),
          "modules.starx.integrations.luckperms.enabled",
          pluginIds.contains("luckperms"));
      manageBoolean(
          editor, changed,
          List.of("modules", "starx.integrations.floodgate", "enabled"),
          "modules.starx.integrations.floodgate.enabled",
          pluginIds.contains("floodgate"));
      manageBoolean(
          editor, changed,
          List.of("modules", "starx.integrations.tab", "enabled"),
          "modules.starx.integrations.tab.enabled",
          pluginIds.contains("tab"));
      manageBoolean(
          editor, changed,
          List.of("modules", "starx.integrations.plan", "enabled"),
          "modules.starx.integrations.plan.enabled",
          pluginIds.contains("plan"));
      boolean raknet = pluginIds.stream()
          .anyMatch(id -> id.contains("geyser") || id.contains("raknetify"));
      manageBoolean(
          editor, changed,
          List.of("modules", "starx.proxytools.raknet", "enabled"),
          "modules.starx.proxytools.raknet.enabled",
          raknet);
    }

    if (bool(auto, "manage-texture-source", true)) {
      manageBoolean(
          editor, changed,
          List.of("website-sync", "textures", "enabled"),
          "website-sync.textures.enabled",
          pluginIds.contains("skinsrestorer"));
    }

    String selectedTarget = null;
    if (bool(auto, "select-auth-target", true) && !serverNames.isEmpty()) {
      String currentTarget = nestedString(root, List.of("uworld", "auth", "target-server"), "");
      boolean unresolved = currentTarget.isBlank() || currentTarget.equalsIgnoreCase("auto");
      boolean missingDefault = firstBoot
          && currentTarget.equalsIgnoreCase("lobby")
          && serverNames.stream().noneMatch(name -> name.equalsIgnoreCase("lobby"));
      if (unresolved || missingDefault) {
        selectedTarget = chooseTargetServer(serverNames);
        if (editor.setScalar(
            List.of("uworld", "auth", "target-server"), quote(selectedTarget))) {
          changed.add("uworld.auth.target-server");
        }
      }
    }

    String currentNodeId = nestedString(root, List.of("website-sync", "node-id"), "");
    if (currentNodeId.isBlank() || currentNodeId.equalsIgnoreCase("auto")) {
      String nodeId = proxyNodeId(hostName);
      if (editor.setScalar(List.of("website-sync", "node-id"), quote(nodeId))) {
        changed.add("website-sync.node-id");
      }
    }

    if (!changed.isEmpty()) {
      editor.writeAtomically(configFile);
      sink.accept("StarX auto-config updated: " + String.join(", ", changed));
    }
    return writeReport(
        configFile, auto, pluginIds, serverNames, selectedTarget, changed, firstBoot, sink);
  }

  static String chooseTargetServer(Set<String> serverNames) {
    List<String> names = serverNames.stream()
        .filter(name -> name != null && !name.isBlank())
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
    for (String preferred : List.of("lobby", "hub", "main", "survival")) {
      for (String name : names) {
        if (name.equalsIgnoreCase(preferred)) {
          return name;
        }
      }
    }
    for (String name : names) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (lower.contains("lobby") || lower.contains("hub")) {
        return name;
      }
    }
    return names.isEmpty() ? "lobby" : names.getFirst();
  }

  static String proxyNodeId(String hostName) {
    String normalized = normalizeId(hostName);
    return normalized.isBlank() ? "proxy-1" : "proxy-" + normalized;
  }

  private static String normalizeId(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9._-]+", "-")
        .replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
    if (normalized.length() > 57) {
      normalized = normalized.substring(0, 57);
    }
    return normalized;
  }

  private static void manageBoolean(
      YamlTextEditor editor,
      List<String> changed,
      List<String> path,
      String displayPath,
      boolean value
  ) {
    if (editor.setScalar(path, Boolean.toString(value))) {
      changed.add(displayPath);
    }
  }

  private static Result writeReport(
      Path configFile,
      Map<String, Object> auto,
      Set<String> pluginIds,
      Set<String> serverNames,
      String selectedTarget,
      List<String> changed,
      boolean firstBoot,
      Consumer<String> logger
  ) throws IOException {
    String reportName = string(auto, "report-file", "auto-detection.json");
    if (reportName.isBlank()) {
      return new Result(!changed.isEmpty(), List.copyOf(changed), null);
    }
    Path parent = configFile.toAbsolutePath().normalize().getParent();
    Path report = parent.resolve(reportName).normalize();
    if (!report.startsWith(parent)) {
      logger.accept("StarX auto-config report path rejected because it escapes the data directory");
      return new Result(!changed.isEmpty(), List.copyOf(changed), null);
    }

    List<String> plugins = pluginIds.stream().sorted().toList();
    List<String> servers = serverNames.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    String json = "{\n"
        + "  \"generatedAt\": " + json(Instant.now().toString()) + ",\n"
        + "  \"platform\": \"velocity\",\n"
        + "  \"firstBoot\": " + firstBoot + ",\n"
        + "  \"plugins\": " + jsonArray(plugins) + ",\n"
        + "  \"servers\": " + jsonArray(servers) + ",\n"
        + "  \"selectedAuthTarget\": "
        + (selectedTarget == null ? "null" : json(selectedTarget)) + ",\n"
        + "  \"changedPaths\": " + jsonArray(changed) + "\n"
        + "}\n";
    writeAtomically(report, json);
    return new Result(!changed.isEmpty(), List.copyOf(changed), report);
  }

  private static String randomSecret() {
    byte[] bytes = new byte[48];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static String localHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception ignored) {
      return "1";
    }
  }

  private static Map<String, Object> rootMap(Object loaded) {
    if (!(loaded instanceof Map<?, ?> map)) {
      return Map.of();
    }
    return stringMap(map);
  }

  private static Map<String, Object> child(Map<String, Object> root, String key) {
    Object value = root.get(key);
    return value instanceof Map<?, ?> map ? stringMap(map) : Map.of();
  }

  private static Map<String, Object> stringMap(Map<?, ?> source) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private static String nestedString(
      Map<String, Object> root,
      List<String> path,
      String fallback
  ) {
    Object current = root;
    for (String part : path) {
      if (!(current instanceof Map<?, ?> map)) {
        return fallback;
      }
      current = map.get(part);
    }
    return current == null ? fallback : String.valueOf(current).trim();
  }

  private static boolean bool(Map<String, Object> node, String key, boolean fallback) {
    Object value = node.get(key);
    if (value instanceof Boolean flag) {
      return flag;
    }
    return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value).trim());
  }

  private static String string(Map<String, Object> node, String key, String fallback) {
    Object value = node.get(key);
    return value == null ? fallback : String.valueOf(value).trim();
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String jsonArray(List<String> values) {
    return values.stream().map(VelocityAutoConfigurator::json)
        .collect(Collectors.joining(", ", "[", "]"));
  }

  private static String json(String value) {
    return quote(value)
        .replace("\b", "\\b")
        .replace("\f", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Files.createDirectories(target.getParent());
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(temporary, content, StandardCharsets.UTF_8);
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public record Result(boolean changed, List<String> changedPaths, Path reportFile) {
  }

  private static final class YamlTextEditor {
    private final List<String> lines;
    private final String newline;

    private YamlTextEditor(String source) {
      this.newline = source.contains("\r\n") ? "\r\n" : "\n";
      this.lines = new ArrayList<>(List.of(source.split("\\R", -1)));
    }

    private boolean setScalar(List<String> expectedPath, String renderedValue) {
      List<String> stack = new ArrayList<>();
      for (int index = 0; index < this.lines.size(); index++) {
        String line = this.lines.get(index);
        Matcher matcher = YAML_KEY.matcher(line);
        if (!matcher.matches()) {
          continue;
        }
        int indent = matcher.group(1).length();
        if (indent % 2 != 0) {
          continue;
        }
        int depth = indent / 2;
        while (stack.size() > depth) {
          stack.removeLast();
        }
        String key = matcher.group(2).trim();
        if (stack.size() == depth) {
          stack.add(key);
        } else {
          stack.set(depth, key);
        }
        if (!stack.equals(expectedPath)) {
          continue;
        }
        String replacement = " ".repeat(indent) + key + ": " + renderedValue;
        if (replacement.equals(line)) {
          return false;
        }
        this.lines.set(index, replacement);
        return true;
      }
      return false;
    }

    private void writeAtomically(Path target) throws IOException {
      VelocityAutoConfigurator.writeAtomically(target, String.join(this.newline, this.lines));
    }
  }
}
