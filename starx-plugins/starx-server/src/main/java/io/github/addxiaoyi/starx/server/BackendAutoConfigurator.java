package io.github.addxiaoyi.starx.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Detects the local backend platform, optional plugins and a sibling Velocity instance.
 * Explicit configuration remains authoritative when the related auto-config switch is disabled.
 */
final class BackendAutoConfigurator {
  private BackendAutoConfigurator() {
  }

  static Result apply(StarxServerPlugin plugin, boolean firstBoot) throws IOException {
    FileConfiguration config = plugin.getConfig();
    if (!config.getBoolean("auto-config.enabled", true)) {
      return writeReport(plugin, firstBoot, List.of(), null, false);
    }

    List<String> changed = new ArrayList<>();
    Path serverRoot = plugin.getServer().getWorldContainer().toPath()
        .toAbsolutePath().normalize();
    String nodeId = config.getString("node-id", "backend");
    if (config.getBoolean("auto-config.infer-node-id", true)
        && (isAutomatic(nodeId) || firstBoot && "backend".equalsIgnoreCase(nodeId))) {
      nodeId = inferNodeId(serverRoot, System.getenv("STARX_NODE_ID"));
      set(config, "node-id", nodeId, changed);
    }

    String serverType = config.getString("server-type", "backend");
    if (config.getBoolean("auto-config.infer-server-type", true)
        && (isAutomatic(serverType) || firstBoot && "backend".equalsIgnoreCase(serverType))) {
      serverType = StarxServerPlugin.inferServerType(nodeId);
      set(config, "server-type", serverType, changed);
    }

    Optional<VelocityEndpoint> velocity = Optional.empty();
    if (config.getBoolean("auto-config.discover-velocity", true)) {
      velocity = discoverVelocity(plugin);
    }

    if (config.getBoolean("auto-config.manage-heartbeat", true)) {
      if (velocity.isPresent()) {
        VelocityEndpoint endpoint = velocity.orElseThrow();
        set(config, "bridge.heartbeat.enabled", true, changed);
        set(config, "bridge.heartbeat.velocity-url", endpoint.baseUrl(), changed);
        set(config, "bridge.heartbeat.api-key", endpoint.apiKey(), changed);
      } else if (config.getBoolean("bridge.heartbeat.enabled", false)
          && config.getString("bridge.heartbeat.api-key", "").isBlank()) {
        set(config, "bridge.heartbeat.enabled", false, changed);
        plugin.getLogger().warning(
            "StarX auto-config disabled empty-server heartbeat: no usable Velocity config found");
      }
    }

    if (!changed.isEmpty()) {
      plugin.saveConfig();
      plugin.reloadConfig();
      plugin.getLogger().info("StarX backend auto-config updated: "
          + changed.stream()
              .filter(path -> !path.equals("bridge.heartbeat.api-key"))
              .collect(Collectors.joining(", ")));
    }
    return writeReport(plugin, firstBoot, changed, velocity.orElse(null), !changed.isEmpty());
  }

  static String inferNodeId(Path serverRoot, String configured) {
    String explicit = normalizeId(configured);
    if (!explicit.isBlank()) {
      return explicit;
    }
    String directory = normalizeId(serverRoot == null || serverRoot.getFileName() == null
        ? "" : serverRoot.getFileName().toString());
    if (!directory.isBlank()
        && !Set.of("server", "paper", "folia", "backend", "minecraft").contains(directory)) {
      return directory;
    }
    String fingerprint = shortHash(serverRoot == null
        ? Path.of(".").toAbsolutePath().normalize().toString()
        : serverRoot.toAbsolutePath().normalize().toString());
    return "backend-" + fingerprint;
  }

  static Optional<VelocityEndpoint> discoverVelocity(StarxServerPlugin plugin) {
    Path serverRoot = plugin.getServer().getWorldContainer().toPath()
        .toAbsolutePath().normalize();
    return discoverVelocity(serverRoot, configuredVelocityPath());
  }

  static Optional<VelocityEndpoint> discoverVelocity(Path serverRoot, Path explicitPath) {
    LinkedHashSet<Path> candidates = new LinkedHashSet<>();
    if (explicitPath != null) {
      candidates.add(explicitPath.toAbsolutePath().normalize());
    }
    if (serverRoot != null) {
      Path root = serverRoot.toAbsolutePath().normalize();
      candidates.add(root.resolve("velocity/plugins/starx/config.yml"));
      Path parent = root.getParent();
      if (parent != null) {
        candidates.add(parent.resolve("velocity/plugins/starx/config.yml"));
        candidates.add(parent.resolve("proxy/plugins/starx/config.yml"));
        candidates.add(parent.resolve("vc/plugins/starx/config.yml"));
        Path grandparent = parent.getParent();
        if (grandparent != null) {
          candidates.add(grandparent.resolve("velocity/plugins/starx/config.yml"));
          candidates.add(grandparent.resolve("proxy/plugins/starx/config.yml"));
          candidates.add(grandparent.resolve("vc/plugins/starx/config.yml"));
        }
      }
    }
    for (Path candidate : candidates) {
      Optional<VelocityEndpoint> loaded = loadVelocity(candidate);
      if (loaded.isPresent()) {
        return loaded;
      }
    }
    return Optional.empty();
  }

  private static Optional<VelocityEndpoint> loadVelocity(Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      return Optional.empty();
    }
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
    String key = yaml.getString("api-key", "").trim();
    int port = yaml.getInt("http.port", 8788);
    String bind = yaml.getString("http.bind", "127.0.0.1").trim();
    if (key.isBlank() || port < 1 || port > 65_535) {
      return Optional.empty();
    }
    Optional<String> runtimeBaseUrl = loadRuntimeEndpoint(path, port);
    String baseUrl = runtimeBaseUrl.orElseGet(() -> httpBaseUrl(bind, port));
    return Optional.of(new VelocityEndpoint(
        path.toAbsolutePath().normalize(),
        baseUrl,
        key,
        runtimeBaseUrl.isPresent()));
  }

  private static Optional<String> loadRuntimeEndpoint(Path configPath, int configuredPort) {
    Path parent = configPath.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      return Optional.empty();
    }
    Path endpointFile = parent.resolve("runtime-endpoint.json");
    Path lockFile = parent.resolve("runtime-endpoint.lock");
    if (!Files.isRegularFile(endpointFile) || !Files.isRegularFile(lockFile)) {
      return Optional.empty();
    }
    try {
      JsonObject root = JsonParser.parseString(
          Files.readString(endpointFile, StandardCharsets.UTF_8)).getAsJsonObject();
      if (jsonInteger(root, "schemaVersion") != 1
          || jsonInteger(root, "configuredPort") != configuredPort) {
        return Optional.empty();
      }
      int effectivePort = jsonInteger(root, "effectivePort");
      long processId = jsonLong(root, "processId");
      String runtimeBind = jsonString(root, "bind");
      if (effectivePort < 1 || effectivePort > 65_535
          || processId < 1
          || runtimeBind.isBlank()
          || ProcessHandle.of(processId).filter(ProcessHandle::isAlive).isEmpty()
          || !runtimeLockHeld(lockFile)) {
        return Optional.empty();
      }
      return Optional.of(httpBaseUrl(runtimeBind, effectivePort));
    } catch (IOException | RuntimeException invalidEndpoint) {
      return Optional.empty();
    }
  }

  private static boolean runtimeLockHeld(Path lockFile) {
    try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
      try {
        FileLock acquired = channel.tryLock();
        if (acquired == null) {
          return true;
        }
        acquired.release();
        return false;
      } catch (OverlappingFileLockException heldInCurrentJvm) {
        return true;
      }
    } catch (IOException unavailable) {
      return false;
    }
  }

  private static int jsonInteger(JsonObject root, String name) {
    if (!root.has(name) || !root.get(name).isJsonPrimitive()) {
      return -1;
    }
    return root.get(name).getAsInt();
  }

  private static long jsonLong(JsonObject root, String name) {
    if (!root.has(name) || !root.get(name).isJsonPrimitive()) {
      return -1L;
    }
    return root.get(name).getAsLong();
  }

  private static String jsonString(JsonObject root, String name) {
    if (!root.has(name) || !root.get(name).isJsonPrimitive()) {
      return "";
    }
    return root.get(name).getAsString().trim();
  }

  private static String httpBaseUrl(String bind, int port) {
    String host = isLoopbackBind(bind) ? "127.0.0.1" : bind.trim();
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    String uriHost = host.contains(":") ? "[" + host + "]" : host;
    return "http://" + uriHost + ":" + port;
  }

  private static boolean isLoopbackBind(String bind) {
    return bind.isBlank()
        || "0.0.0.0".equals(bind)
        || "::".equals(bind)
        || "localhost".equalsIgnoreCase(bind)
        || "127.0.0.1".equals(bind)
        || "::1".equals(bind);
  }

  private static Path configuredVelocityPath() {
    String property = System.getProperty("starx.velocity.config", "").trim();
    if (!property.isBlank()) {
      return Path.of(property);
    }
    String environment = System.getenv("STARX_VELOCITY_CONFIG");
    return environment == null || environment.isBlank() ? null : Path.of(environment.trim());
  }

  private static Result writeReport(
      StarxServerPlugin plugin,
      boolean firstBoot,
      List<String> changed,
      VelocityEndpoint velocity,
      boolean configChanged
  ) throws IOException {
    String name = plugin.getConfig().getString(
        "auto-config.report-file", "auto-detection.json").trim();
    if (name.isBlank()) {
      return new Result(configChanged, List.copyOf(changed), null);
    }
    Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
    Path report = data.resolve(name).normalize();
    if (!report.startsWith(data)) {
      plugin.getLogger().warning("StarX auto-config report path escapes the plugin directory");
      return new Result(configChanged, List.copyOf(changed), null);
    }

    List<String> plugins = new ArrayList<>();
    for (Plugin installed : plugin.getServer().getPluginManager().getPlugins()) {
      plugins.add(installed.getName());
    }
    plugins.sort(String.CASE_INSENSITIVE_ORDER);
    ServerPlatform platform = ServerPlatform.detect();
    String nodeId = plugin.getConfig().getString("node-id", "backend");
    String serverType = plugin.getConfig().getString("server-type", "backend");
    String discovered = velocity == null ? null : velocity.configPath().toString();
    String velocityBaseUrl = velocity == null ? null : velocity.baseUrl();
    boolean velocityRuntimeEndpoint = velocity != null && velocity.runtimeEndpoint();
    List<String> publicChanges = changed.stream()
        .filter(path -> !path.equals("bridge.heartbeat.api-key"))
        .toList();

    String json = "{\n"
        + "  \"generatedAt\": " + json(Instant.now().toString()) + ",\n"
        + "  \"platform\": " + json(platform.name().toLowerCase(Locale.ROOT)) + ",\n"
        + "  \"executionModel\": " + json(platform.executionModel()) + ",\n"
        + "  \"firstBoot\": " + firstBoot + ",\n"
        + "  \"nodeId\": " + json(nodeId) + ",\n"
        + "  \"serverType\": " + json(serverType) + ",\n"
        + "  \"plugins\": " + jsonArray(plugins) + ",\n"
        + "  \"velocityConfig\": " + (discovered == null ? "null" : json(discovered)) + ",\n"
        + "  \"velocityBaseUrl\": "
        + (velocityBaseUrl == null ? "null" : json(velocityBaseUrl)) + ",\n"
        + "  \"velocityRuntimeEndpoint\": " + velocityRuntimeEndpoint + ",\n"
        + "  \"changedPaths\": " + jsonArray(publicChanges) + "\n"
        + "}\n";
    Files.createDirectories(data);
    Files.writeString(report, json, StandardCharsets.UTF_8);
    return new Result(configChanged, List.copyOf(changed), report);
  }

  private static boolean isAutomatic(String value) {
    return value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim());
  }

  private static void set(
      FileConfiguration config,
      String path,
      Object value,
      List<String> changed
  ) {
    Object existing = config.get(path);
    if (existing != null && String.valueOf(existing).equals(String.valueOf(value))) {
      return;
    }
    config.set(path, value);
    changed.add(path);
  }

  private static String normalizeId(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9._-]+", "-")
        .replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
    return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
  }

  private static String shortHash(String value) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 4);
    } catch (Exception error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private static String jsonArray(List<String> values) {
    return values.stream().map(BackendAutoConfigurator::json)
        .collect(Collectors.joining(", ", "[", "]"));
  }

  private static String json(String value) {
    return "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\b", "\\b")
        .replace("\f", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\"";
  }

  record VelocityEndpoint(
      Path configPath,
      String baseUrl,
      String apiKey,
      boolean runtimeEndpoint) {
  }

  record Result(boolean changed, List<String> changedPaths, Path reportFile) {
  }
}
