package io.github.addxiaoyi.starx.website;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class NodeCapabilities {
  public static final String NETWORK_STATUS = "network.status";
  public static final String PLAYERS_SNAPSHOT = "players.snapshot";
  public static final String SERVER_STATUS = "server.status";
  public static final String SERVER_COMMANDS = "server.commands";
  public static final String SKIN_REFRESH = "skin.refresh";
  public static final String AUTH_EVENTS = "auth.events";

  private static final Set<String> ALLOWED = Set.of(
      NETWORK_STATUS,
      PLAYERS_SNAPSHOT,
      SERVER_STATUS,
      SERVER_COMMANDS,
      SKIN_REFRESH,
      AUTH_EVENTS);

  private NodeCapabilities() {
  }

  public static List<String> normalize(Iterable<String> values) {
    TreeSet<String> normalized = new TreeSet<>();
    if (values != null) {
      for (String value : values) {
        String capability = value == null ? "" : value.trim();
        if (!capability.isEmpty()) {
          if (!ALLOWED.contains(capability)) {
            throw new IllegalArgumentException("Unsupported website capability: " + capability);
          }
          normalized.add(capability);
        }
      }
    }
    return List.copyOf(normalized);
  }
}
