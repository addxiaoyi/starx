package io.github.addxiaoyi.starx.velocity.network;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generates a StarX-owned FRP proxy and reads back the port atomically assigned by frps. */
public final class FrpManagedProxy {
  private static final Pattern ENDPOINT = Pattern.compile(
      "(?:\\[([0-9a-fA-F:]+)]|([A-Za-z0-9._-]+)):(\\d{1,5})");
  private static final Pattern LABELED_PORT = Pattern.compile(
      "(?i)(?:remote[_ -]?(?:addr|address|port)|server[_ -]?port)\\s*[:=]\\s*"
          + "(?:[^\\s,:=]+:)?(\\d{1,5})");
  private static final Pattern INCLUDES_ASSIGNMENT = Pattern.compile(
      "(?ms)^\\s*includes\\s*=\\s*\\[(.*?)]\\s*(?:#.*)?$");
  private static final Pattern QUOTED_INCLUDE = Pattern.compile(
      "\"([^\"]*)\"|'([^']*)'");

  private FrpManagedProxy() {
  }

  public static String render(NetworkAutomationConfig.Frp config) {
    Objects.requireNonNull(config, "config");
    if (config.mode() != NetworkAutomationConfig.Frp.Mode.MANAGED) {
      throw new IllegalArgumentException("FRP config rendering requires managed mode");
    }
    if (config.remotePort() != 0) {
      throw new IllegalArgumentException(
          "managed FRP must use remote-port 0; never scan or preselect a port");
    }
    return "[[proxies]]\n"
        + "name = \"" + toml(config.proxyName()) + "\"\n"
        + "type = \"tcp\"\n"
        + "localIP = \"" + toml(config.localAddress()) + "\"\n"
        + "localPort = " + config.localPort() + "\n"
        + "remotePort = 0\n"
        + "transport.useEncryption = true\n"
        + "transport.useCompression = true\n"
        + "healthCheck.type = \"tcp\"\n"
        + "healthCheck.timeoutSeconds = 3\n"
        + "healthCheck.maxFailed = 3\n"
        + "healthCheck.intervalSeconds = 10\n";
  }

  /**
   * Returns a port only when it occurs on the status line for the requested proxy.
   * This prevents publishing a guessed or merely locally-free port.
   */
  public static OptionalInt parseAssignedPort(String statusOutput, String proxyName) {
    Objects.requireNonNull(proxyName, "proxyName");
    if (statusOutput == null || statusOutput.isBlank()) {
      return OptionalInt.empty();
    }
    for (String line : statusOutput.split("\\R")) {
      if (!containsToken(line, proxyName)) {
        continue;
      }
      Matcher labeled = LABELED_PORT.matcher(line);
      int labeledPort = -1;
      while (labeled.find()) {
        labeledPort = parsePort(labeled.group(1));
      }
      if (labeledPort > 0) {
        return OptionalInt.of(labeledPort);
      }

      Matcher endpoint = ENDPOINT.matcher(line);
      int endpointPort = -1;
      while (endpoint.find()) {
        endpointPort = parsePort(endpoint.group(3));
      }
      if (endpointPort > 0) {
        return OptionalInt.of(endpointPort);
      }
    }
    return OptionalInt.empty();
  }

  /**
   * Verifies that the main frpc TOML actually includes the managed file.
   * Comments and unrelated strings are ignored. Exact paths and bounded glob entries are supported.
   */
  static boolean mainConfigIncludes(Path mainConfig, Path managedConfig, String source) {
    Objects.requireNonNull(mainConfig, "mainConfig");
    Objects.requireNonNull(managedConfig, "managedConfig");
    if (source == null || source.isBlank()) {
      return false;
    }

    String target = normalizePath(managedConfig.toAbsolutePath().normalize().toString());
    Path parent = mainConfig.toAbsolutePath().normalize().getParent();
    String base = normalizePath((parent == null
        ? Path.of(".").toAbsolutePath().normalize()
        : parent).toString());

    Matcher assignments = INCLUDES_ASSIGNMENT.matcher(source);
    while (assignments.find()) {
      Matcher entries = QUOTED_INCLUDE.matcher(assignments.group(1));
      while (entries.find()) {
        String raw = entries.group(1) != null
            ? decodeTomlBasicString(entries.group(1))
            : entries.group(2);
        String candidate = absolutePattern(base, raw);
        if (globMatches(candidate, target)) {
          return true;
        }
      }
    }
    return false;
  }

  public static String publicUrl(NetworkAutomationConfig.Frp config, int assignedPort) {
    Objects.requireNonNull(config, "config");
    requirePort(assignedPort);
    if (!config.publicUrl().isBlank()) {
      URI uri = URI.create(config.publicUrl());
      if (!"http".equalsIgnoreCase(uri.getScheme())
          && !"https".equalsIgnoreCase(uri.getScheme())) {
        throw new IllegalArgumentException("FRP public URL must use HTTP or HTTPS");
      }
      if (uri.getHost() == null || uri.getUserInfo() != null
          || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException("FRP public URL must be a clean HTTP(S) base URL");
      }
      String value = config.publicUrl();
      while (value.endsWith("/")) {
        value = value.substring(0, value.length() - 1);
      }
      return value;
    }
    if (config.publicHost().isBlank()) {
      throw new IllegalArgumentException(
          "network-automation.frp.public-host is required after port allocation");
    }
    String host = config.publicHost();
    if (host.contains(":") && !host.startsWith("[")) {
      host = "[" + host + "]";
    }
    boolean defaultPort = ("http".equals(config.publicScheme()) && assignedPort == 80)
        || ("https".equals(config.publicScheme()) && assignedPort == 443);
    return config.publicScheme() + "://" + host + (defaultPort ? "" : ":" + assignedPort);
  }

  private static String decodeTomlBasicString(String value) {
    return value
        .replace("\\\\", "\u0000")
        .replace("\\\"", "\"")
        .replace("\u0000", "\\");
  }

  private static String absolutePattern(String base, String entry) {
    String normalized = normalizePath(entry == null ? "" : entry.trim());
    boolean absolute = normalized.startsWith("/")
        || normalized.matches("^[A-Za-z]:/.*");
    return normalizeSegments(absolute ? normalized : base + "/" + normalized);
  }

  private static String normalizePath(String value) {
    return value.replace('\\', '/');
  }

  private static String normalizeSegments(String value) {
    String prefix = "";
    String remaining = value;
    if (remaining.matches("^[A-Za-z]:/.*")) {
      prefix = remaining.substring(0, 2);
      remaining = remaining.substring(2);
    } else if (remaining.startsWith("/")) {
      prefix = "/";
    }
    Deque<String> segments = new ArrayDeque<>();
    for (String segment : remaining.split("/+")) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        if (!segments.isEmpty()) {
          segments.removeLast();
        }
        continue;
      }
      segments.addLast(segment);
    }
    String joined = String.join("/", segments);
    if ("/".equals(prefix)) {
      return "/" + joined;
    }
    return prefix.isEmpty() ? joined : prefix + "/" + joined;
  }

  private static boolean globMatches(String pattern, String value) {
    StringBuilder regex = new StringBuilder("^");
    for (int index = 0; index < pattern.length(); index++) {
      char current = pattern.charAt(index);
      if (current == '*') {
        boolean doubleStar = index + 1 < pattern.length() && pattern.charAt(index + 1) == '*';
        regex.append(doubleStar ? ".*" : "[^/]*");
        if (doubleStar) {
          index++;
        }
      } else if (current == '?') {
        regex.append("[^/]");
      } else {
        if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
          regex.append('\\');
        }
        regex.append(current);
      }
    }
    regex.append('$');
    return Pattern.compile(regex.toString()).matcher(value).matches();
  }

  private static boolean containsToken(String line, String token) {
    return Pattern.compile("(?<![A-Za-z0-9._-])" + Pattern.quote(token)
        + "(?![A-Za-z0-9._-])").matcher(line).find();
  }

  private static int parsePort(String value) {
    try {
      int port = Integer.parseInt(value);
      return port > 0 && port <= 65535 ? port : -1;
    } catch (NumberFormatException error) {
      return -1;
    }
  }

  private static void requirePort(int port) {
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException("assigned FRP port must be between 1 and 65535");
    }
  }

  private static String toml(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
