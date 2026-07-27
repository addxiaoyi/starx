package io.github.addxiaoyi.starx.website;

import java.util.Comparator;
import java.util.List;

public record NodeSnapshot(
    String pluginVersion,
    String minecraftVersion,
    Integer onlinePlayers,
    Integer maxPlayers,
    Double tps,
    Double mspt,
    boolean maintenance,
    List<ServerSnapshot> servers
) {
  public NodeSnapshot {
    pluginVersion = ServerSnapshot.boundedNullable(pluginVersion, 32, "pluginVersion");
    minecraftVersion = ServerSnapshot.boundedNullable(
        minecraftVersion, 32, "minecraftVersion");
    onlinePlayers = ServerSnapshot.boundedInteger(
        onlinePlayers, 0, 100_000, "onlinePlayers");
    maxPlayers = ServerSnapshot.boundedInteger(maxPlayers, 0, 100_000, "maxPlayers");
    tps = ServerSnapshot.boundedDouble(tps, 0, 100, "tps");
    mspt = ServerSnapshot.boundedDouble(mspt, 0, 60_000, "mspt");
    List<ServerSnapshot> normalized = servers == null ? List.of() : List.copyOf(servers);
    if (normalized.size() > 128) {
      throw new IllegalArgumentException("A node snapshot may contain at most 128 child servers");
    }
    servers = normalized.stream()
        .sorted(Comparator.comparing(ServerSnapshot::nodeId))
        .toList();
  }

  public NodeSnapshot offline() {
    return new NodeSnapshot(
        this.pluginVersion,
        this.minecraftVersion,
        0,
        this.maxPlayers,
        null,
        null,
        this.maintenance,
        this.servers.stream().map(ServerSnapshot::offline).toList());
  }
}
