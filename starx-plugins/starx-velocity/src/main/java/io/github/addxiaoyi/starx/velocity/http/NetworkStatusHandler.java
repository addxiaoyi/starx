package io.github.addxiaoyi.starx.velocity.http;

import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNode;
import java.time.Duration;
import io.github.addxiaoyi.starx.common.platform.NodeHealthStateMachine;
import io.github.addxiaoyi.starx.velocity.status.NetworkStatusSnapshot;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;

final class NetworkStatusHandler {

  private static final Duration BRIDGE_MAX_AGE = Duration.ofMinutes(5);
  // Status is operational telemetry, not a per-tick feed. Keep probes sparse.
  private static final Duration PING_CACHE_TTL = Duration.ofSeconds(10);
  private static final Duration SNAPSHOT_CACHE_TTL = Duration.ofSeconds(1);

  private final ProxyServer proxy;
  private final BackendNodeRegistry backendNodes;
  private final Supplier<Map<String, Object>> metricsSupplier;
  private final Map<String, CachedPing> pingCache = new ConcurrentHashMap<>();
  private volatile SnapshotCache snapshotCache;

  NetworkStatusHandler(ProxyServer proxy, BackendNodeRegistry backendNodes) {
    this(proxy, backendNodes, Map::of);
  }

  NetworkStatusHandler(
      ProxyServer proxy,
      BackendNodeRegistry backendNodes,
      Supplier<Map<String, Object>> metricsSupplier) {
    this.proxy = Objects.requireNonNull(proxy, "proxy");
    this.backendNodes = Objects.requireNonNull(backendNodes, "backendNodes");
    this.metricsSupplier = Objects.requireNonNull(metricsSupplier, "metricsSupplier");
  }

  void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... auth) {
    routes.get("/v1/network/status", this.chain(this::handle, auth));
  }

  private RouteRegistrar.RouteHandler chain(
      RouteRegistrar.RouteHandler handler,
      RouteRegistrar.RouteHandler... auth) {
    return ctx -> {
      for (RouteRegistrar.RouteHandler filter : auth) {
        filter.handle(ctx);
      }
      handler.handle(ctx);
    };
  }

  private void handle(JsonHttpExchange ctx) throws IOException {
    ctx.status(200).json(NetworkStatusPayload.from(this.snapshot(), this.metricsSupplier.get()));
  }

  private NetworkStatusSnapshot snapshot() {
    Instant now = Instant.now();
    List<RegisteredServer> registeredServers = List.copyOf(this.proxy.getAllServers());
    SnapshotCache cachedSnapshot = this.snapshotCache;
    if (cachedSnapshot != null && now.isBefore(cachedSnapshot.expiresAt())) {
      return cachedSnapshot.snapshot();
    }

    // Remove deleted servers so a long-lived proxy does not retain old names forever.
    java.util.Set<String> activeNames = registeredServers.stream()
        .map(server -> server.getServerInfo().getName())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    this.pingCache.keySet().removeIf(name -> !activeNames.contains(name));

    Map<String, Integer> capacities = new LinkedHashMap<>();
    for (RegisteredServer server : registeredServers) {
      String name = server.getServerInfo().getName();
      CachedPing cached = this.pingCache.get(name);
      if (cached == null || now.isAfter(cached.expiresAt())) {
        if (cached != null) this.pingCache.remove(name, cached);
        this.refreshPing(name, server);
        cached = new CachedPing(-1, now.plus(PING_CACHE_TTL));
      }
      capacities.put(name, cached.capacity());
    }
    List<NetworkStatusSnapshot.ServerStatus> servers = registeredServers.stream()
        .map(server -> new NetworkStatusSnapshot.ServerStatus(
            server.getServerInfo().getName(),
            server.getPlayersConnected().size(),
            BackendCapacityResolver.resolve(
                this.backendNodes,
                server.getServerInfo().getName(),
                now,
                capacities.getOrDefault(server.getServerInfo().getName(), -1)),
            this.features(server.getServerInfo().getName(), now)))
        .toList();
    NetworkStatusSnapshot snapshot = NetworkStatusSnapshot.of(
        now,
        this.proxy.getPlayerCount(),
        this.proxy.getConfiguration().getShowMaxPlayers(),
        servers);
    this.snapshotCache = new SnapshotCache(snapshot, now.plus(SNAPSHOT_CACHE_TTL));
    return snapshot;
  }

  private void refreshPing(String name, RegisteredServer server) {
    if (this.pingCache.putIfAbsent(
        name, new CachedPing(-1, Instant.now().plus(PING_CACHE_TTL))) != null) {
      return;
    }
    server.ping().orTimeout(1, TimeUnit.SECONDS).whenComplete((ping, error) -> {
      int capacity = error == null && ping != null
          ? ping.getPlayers().map(ServerPing.Players::getMax).orElse(-1)
          : -1;
      this.pingCache.put(name, new CachedPing(capacity, Instant.now().plus(PING_CACHE_TTL)));
    });
  }

  private record CachedPing(int capacity, Instant expiresAt) {}

  private record SnapshotCache(NetworkStatusSnapshot snapshot, Instant expiresAt) {}

  private Map<String, String> features(String serverName, Instant now) {
    BackendNode node = this.backendNodes.find(serverName).orElse(null);
    NodeHealthStateMachine.Snapshot health = this.backendNodes.health(serverName);
    return featuresOf(node, now, health);
  }

  static Map<String, String> featuresOf(BackendNode node, Instant now) {
    return featuresOf(node, now,
        new NodeHealthStateMachine.Snapshot(
            NodeHealthStateMachine.State.HEALTHY, 100, 0, 0));
  }

  static Map<String, String> featuresOf(
      BackendNode node, Instant now, NodeHealthStateMachine.Snapshot health) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(health, "health");
    if (node == null) {
      return Map.of(
          "skinProvider", "unknown",
          "skinBridge", "unavailable",
          "bridgeState", "unseen",
          "healthState", health.state().name(),
          "admissionWeight", Integer.toString(health.admissionWeight()),
          "transport", "ping-only",
          "httpCommandsAccepted", "0",
          "httpCommandsDelivered", "0",
          "httpCommandsRejected", "0",
          "httpCommandsQueued", "0");
    }
    boolean stale = node.isStale(now, BRIDGE_MAX_AGE);
    Map<String, String> features = new LinkedHashMap<>();
    features.put("nodeId", node.declaredNodeId());
    features.put("platform", node.platform().name());
    features.put("capabilities", String.join(
        ",", new java.util.TreeSet<>(node.capabilities())));
    features.put("execution", node.status().getOrDefault("execution", "unknown"));
    features.put("minecraft", node.status().getOrDefault("minecraft", "unknown"));
    features.put("implementation", node.status().getOrDefault("implementation", "unknown"));
    features.put("uptimeMillis", node.status().getOrDefault("uptimeMillis", "0"));
    features.put("lastSeen", node.lastSeen().toString());
    features.put("skinProvider", node.status().getOrDefault("skinProvider", "none"));
    features.put("skinBridge", node.status().getOrDefault("skinBridge", "unavailable"));
    features.put("bridgeState", stale ? "stale" : "linked");
    features.put("healthState", health.state().name());
    features.put("admissionWeight", Integer.toString(health.admissionWeight()));
    features.put("latencyMs", node.status().getOrDefault("latencyMs", "unknown"));
    features.put("mspt", node.status().getOrDefault("mspt", "unknown"));
    features.put("heartbeatRttMs", node.status().getOrDefault("heartbeatRttMs", "unknown"));
    features.put("packetLoss", node.status().getOrDefault("packetLoss", "unknown"));
    features.put("transport", node.status().getOrDefault("transport", "player-carrier"));
    features.put(
        "httpCommandsAccepted",
        node.status().getOrDefault("httpCommandsAccepted", "0"));
    features.put(
        "httpCommandsDelivered",
        node.status().getOrDefault("httpCommandsDelivered", "0"));
    features.put(
        "httpCommandsRejected",
        node.status().getOrDefault("httpCommandsRejected", "0"));
    features.put(
        "httpCommandsQueued",
        node.status().getOrDefault("httpCommandsQueued", "0"));
    return Map.copyOf(features);
  }
}
