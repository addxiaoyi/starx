package io.github.addxiaoyi.starx.velocity.routing;

import io.github.addxiaoyi.starx.common.platform.NodeHealthStateMachine;
import io.github.addxiaoyi.starx.common.platform.ServerRoutingEngine;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNode;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class BackendRoutingService {
  private static final int DEFAULT_ADMISSIONS_PER_MINUTE = 20;

  private final BackendNodeRegistry registry;
  private final ServerRoutingEngine engine;
  private final Clock clock;
  private final Duration staleAfter;

  public BackendRoutingService(BackendNodeRegistry registry) {
    this(registry, new ServerRoutingEngine(), Clock.systemUTC(), Duration.ofSeconds(45));
  }

  BackendRoutingService(
      BackendNodeRegistry registry,
      ServerRoutingEngine engine,
      Clock clock,
      Duration staleAfter) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.engine = Objects.requireNonNull(engine, "engine");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
    if (staleAfter.isZero() || staleAfter.isNegative()) {
      throw new IllegalArgumentException("staleAfter must be positive");
    }
  }

  public Optional<ServerRoutingEngine.Decision> select(
      String preferredNode,
      Map<String, Integer> queueSizes) {
    if (preferredNode == null || preferredNode.isBlank()) return Optional.empty();
    Map<String, Integer> queues = queueSizes == null ? Map.of() : Map.copyOf(queueSizes);
    String requestedType = this.registry.find(preferredNode)
        .map(this::serverType)
        .orElseGet(() -> inferServerType(preferredNode));
    Instant now = this.clock.instant();
    List<ServerRoutingEngine.Node> candidates = new ArrayList<>();
    for (BackendNode node : this.registry.all()) {
      candidates.add(toRoutingNode(node, queues.getOrDefault(node.registeredServer(), 0), now));
    }
    if (candidates.isEmpty()) return Optional.empty();
    try {
      return Optional.of(this.engine.select(
          new ServerRoutingEngine.Request(requestedType, preferredNode, Set.of()), candidates));
    } catch (IllegalStateException unavailable) {
      return Optional.empty();
    }
  }

  private ServerRoutingEngine.Node toRoutingNode(BackendNode node, int queued, Instant now) {
    NodeHealthStateMachine.Snapshot health = this.registry.health(node.registeredServer());
    boolean stale = node.isStale(now, this.staleAfter);
    boolean online = !stale && health.state() != NodeHealthStateMachine.State.OFFLINE;
    boolean maintenance = Boolean.parseBoolean(node.status().getOrDefault("maintenance", "false"));
    boolean draining = health.state() == NodeHealthStateMachine.State.DRAINING;
    int players = Math.max(0, node.onlinePlayers());
    int reportedCapacity = node.maxPlayers();
    int capacity = reportedCapacity > 0 ? reportedCapacity : Math.max(players + 1, 1);
    return new ServerRoutingEngine.Node(
        node.registeredServer(),
        serverType(node),
        online,
        maintenance,
        draining,
        capacity,
        players,
        parseDouble(node.status().get("mspt"), 0.0),
        parseInt(node.status().get("latencyMs"), 0),
        Math.max(0, queued),
        positiveInt(node.status().get("admissionsPerMinute"), DEFAULT_ADMISSIONS_PER_MINUTE),
        health.admissionWeight());
  }

  private String serverType(BackendNode node) {
    String reported = node.status().get("serverType");
    return reported == null || reported.isBlank()
        ? inferServerType(node.registeredServer())
        : reported.trim().toLowerCase(Locale.ROOT);
  }

  static String inferServerType(String serverName) {
    String normalized = serverName.trim().toLowerCase(Locale.ROOT);
    String withoutShard = normalized.replaceFirst("[-_.]?\\d+$", "");
    return withoutShard.isBlank() ? normalized : withoutShard;
  }

  private static int positiveInt(String value, int fallback) {
    int parsed = parseInt(value, fallback);
    return parsed > 0 ? parsed : fallback;
  }

  private static int parseInt(String value, int fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static double parseDouble(String value, double fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      double parsed = Double.parseDouble(value.trim());
      return Double.isFinite(parsed) && parsed >= 0.0 ? parsed : fallback;
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
