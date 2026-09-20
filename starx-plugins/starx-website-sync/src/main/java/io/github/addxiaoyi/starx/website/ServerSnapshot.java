package io.github.addxiaoyi.starx.website;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record ServerSnapshot(
    String nodeId,
    String name,
    String platform,
    String minecraftVersion,
    WebsiteNodeStatus status,
    Integer onlinePlayers,
    Integer maxPlayers,
    Double tps,
    Double mspt,
    boolean maintenance,
    List<String> capabilities
) {
  private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,63}");

  public ServerSnapshot {
    nodeId = Objects.requireNonNullElse(nodeId, "").trim();
    if (!NODE_ID.matcher(nodeId).matches()) {
      throw new IllegalArgumentException("Invalid child server nodeId: " + nodeId);
    }
    name = bounded(name == null || name.isBlank() ? nodeId : name.trim(), 80, "name");
    platform = boundedNullable(platform, 32, "platform");
    minecraftVersion = boundedNullable(minecraftVersion, 32, "minecraftVersion");
    status = status == null ? WebsiteNodeStatus.UNKNOWN : status;
    onlinePlayers = boundedInteger(onlinePlayers, 0, 100_000, "onlinePlayers");
    maxPlayers = boundedInteger(maxPlayers, 0, 100_000, "maxPlayers");
    tps = boundedDouble(tps, 0, 100, "tps");
    mspt = boundedDouble(mspt, 0, 60_000, "mspt");
    capabilities = NodeCapabilities.normalize(capabilities);
  }

  public ServerSnapshot offline() {
    return new ServerSnapshot(
        this.nodeId,
        this.name,
        this.platform,
        this.minecraftVersion,
        WebsiteNodeStatus.OFFLINE,
        0,
        this.maxPlayers,
        null,
        null,
        this.maintenance,
        this.capabilities);
  }

  static String boundedNullable(String value, int max, String label) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return bounded(value.trim(), max, label);
  }

  static String bounded(String value, int max, String label) {
    if (value.length() > max) {
      throw new IllegalArgumentException(label + " exceeds " + max + " characters");
    }
    return value;
  }

  static Integer boundedInteger(Integer value, int min, int max, String label) {
    if (value != null && (value < min || value > max)) {
      throw new IllegalArgumentException(label + " must be between " + min + " and " + max);
    }
    return value;
  }

  static Double boundedDouble(Double value, double min, double max, String label) {
    if (value != null && (!Double.isFinite(value) || value < min || value > max)) {
      throw new IllegalArgumentException(label + " must be between " + min + " and " + max);
    }
    return value;
  }
}
