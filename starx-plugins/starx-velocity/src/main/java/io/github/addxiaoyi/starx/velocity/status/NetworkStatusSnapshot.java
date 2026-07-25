package io.github.addxiaoyi.starx.velocity.status;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;

public record NetworkStatusSnapshot(
    Instant collectedAt,
    int onlinePlayers,
    int maxPlayers,
    List<ServerStatus> servers) {

  public NetworkStatusSnapshot {
    collectedAt = Objects.requireNonNull(collectedAt, "collectedAt");
    if (onlinePlayers < 0 || maxPlayers < 0) {
      throw new IllegalArgumentException("network player counts cannot be negative");
    }
    servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
  }

  public static NetworkStatusSnapshot of(
      Instant collectedAt,
      int onlinePlayers,
      int maxPlayers,
      List<ServerStatus> servers) {
    List<ServerStatus> ordered = Objects.requireNonNull(servers, "servers").stream()
        .sorted(Comparator.comparing(ServerStatus::name))
        .toList();
    Set<String> names = new HashSet<>();
    for (ServerStatus server : ordered) {
      if (!names.add(server.name())) {
        throw new IllegalArgumentException("duplicate server name: " + server.name());
      }
    }
    return new NetworkStatusSnapshot(collectedAt, onlinePlayers, maxPlayers, ordered);
  }

  public record ServerStatus(
      String name,
      int onlinePlayers,
      int maxPlayers,
      Map<String, String> features
  ) {
    public ServerStatus(String name, int onlinePlayers, int maxPlayers) {
      this(name, onlinePlayers, maxPlayers, Map.of());
    }

    public ServerStatus {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("server name cannot be blank");
      }
      name = name.trim();
      if (onlinePlayers < 0 || maxPlayers < 0) {
        throw new IllegalArgumentException("server player counts cannot be negative");
      }
      features = Map.copyOf(Objects.requireNonNull(features, "features"));
    }
  }
}
