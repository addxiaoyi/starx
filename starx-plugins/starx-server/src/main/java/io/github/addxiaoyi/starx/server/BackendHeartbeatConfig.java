package io.github.addxiaoyi.starx.server;

import java.net.URI;
import java.time.Duration;

record BackendHeartbeatConfig(
    boolean enabled,
    URI velocityUrl,
    String apiKey,
    String serverName,
    Duration interval,
    Duration timeout
) {
  private static final String SERVER_PATTERN = "[A-Za-z0-9_.-]{1,64}";

  static BackendHeartbeatConfig create(
      boolean enabled,
      String velocityUrl,
      String apiKey,
      String serverName,
      int intervalSeconds,
      int timeoutMillis
  ) {
    URI uri;
    try {
      uri = URI.create(requireText(velocityUrl, "bridge.heartbeat.velocity-url"));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "bridge.heartbeat.velocity-url must be a valid HTTP(S) origin", error);
    }
    String scheme = uri.getScheme();
    boolean isHttp = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    if (!isHttp || uri.getHost() == null || uri.getUserInfo() != null
        || uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          "bridge.heartbeat.velocity-url must be an HTTP(S) origin without credentials");
    }

    String name = requireText(serverName, "node-id");
    if (!name.matches(SERVER_PATTERN)) {
      throw new IllegalArgumentException("node-id must match " + SERVER_PATTERN);
    }
    if (intervalSeconds < 5 || intervalSeconds > 300) {
      throw new IllegalArgumentException(
          "bridge.heartbeat.interval-seconds must be between 5 and 300");
    }
    if (timeoutMillis < 100 || timeoutMillis > 60_000) {
      throw new IllegalArgumentException(
          "bridge.heartbeat.timeout-ms must be between 100 and 60000");
    }

    String key = apiKey == null ? "" : apiKey.trim();
    if (enabled && key.isEmpty()) {
      throw new IllegalArgumentException(
          "bridge.heartbeat.api-key must be configured when heartbeat is enabled");
    }
    return new BackendHeartbeatConfig(
        enabled,
        uri,
        key,
        name,
        Duration.ofSeconds(intervalSeconds),
        Duration.ofMillis(timeoutMillis));
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value.trim();
  }
}
