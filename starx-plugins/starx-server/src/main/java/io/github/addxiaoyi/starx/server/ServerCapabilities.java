package io.github.addxiaoyi.starx.server;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class ServerCapabilities {
  private static final Set<String> COMMON = Set.of(
      "bridge.http-exchange",
      "bridge.v1",
      "player.carrier",
      "public.player-count",
      "players.snapshot",
      "server.commands",
      "server.status");

  private ServerCapabilities() {
  }

  public static Set<String> forPlatform(ServerPlatform platform) {
    Objects.requireNonNull(platform, "platform");
    Set<String> capabilities = new LinkedHashSet<>(COMMON);
    if (platform == ServerPlatform.FOLIA) {
      capabilities.add("scheduler.global");
      capabilities.add("scheduler.region");
      capabilities.add("world.regionized");
    } else {
      capabilities.add("scheduler.main");
      capabilities.add("world.paper");
    }
    return Set.copyOf(capabilities);
  }
}
