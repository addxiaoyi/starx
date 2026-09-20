package io.github.addxiaoyi.starx.velocity.http;

import io.github.addxiaoyi.starx.velocity.status.NetworkStatusSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;

final class NetworkStatusPayload {

  private NetworkStatusPayload() {
  }

  static Map<String, Object> from(NetworkStatusSnapshot snapshot) {
    return from(snapshot, Map.of());
  }

  static Map<String, Object> from(NetworkStatusSnapshot snapshot, Map<String, Object> metrics) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(metrics, "metrics");
    List<Map<String, Object>> servers = snapshot.servers().stream().map(server -> {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("name", server.name());
      payload.put("onlinePlayers", server.onlinePlayers());
      payload.put("maxPlayers", server.maxPlayers());
      payload.putAll(server.features());
      return Map.copyOf(payload);
    }).toList();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("collectedAt", snapshot.collectedAt().toString());
    payload.put("onlinePlayers", snapshot.onlinePlayers());
    payload.put("maxPlayers", snapshot.maxPlayers());
    payload.put("servers", servers);
    if (!metrics.isEmpty()) {
      payload.put("metrics", Map.copyOf(metrics));
    }
    return Map.copyOf(payload);
  }
}
