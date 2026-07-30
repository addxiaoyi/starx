package io.github.addxiaoyi.starx.velocity.bridge;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.common.platform.NodeHealthStateMachine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class BackendNodeRegistry {
  private static final Duration DEFAULT_OFFLINE_TTL = Duration.ofMinutes(30);
  private static final int DEFAULT_MAX_NODES = 512;

  private final ConcurrentMap<String, BackendNode> nodes = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, NodeHealthStateMachine> health = new ConcurrentHashMap<>();
  private final Set<String> observedServers = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<String, Instant> lastActivity = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration offlineTtl;
  private final int maxNodes;
  private final Object retentionLock = new Object();

  public BackendNodeRegistry() {
    this(Clock.systemUTC(), DEFAULT_OFFLINE_TTL, DEFAULT_MAX_NODES);
  }

  BackendNodeRegistry(Clock clock, Duration offlineTtl, int maxNodes) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.offlineTtl = Objects.requireNonNull(offlineTtl, "offlineTtl");
    if (offlineTtl.isZero() || offlineTtl.isNegative()) {
      throw new IllegalArgumentException("offlineTtl must be positive");
    }
    if (maxNodes < 1 || maxNodes > 16_384) {
      throw new IllegalArgumentException("maxNodes must be between 1 and 16384");
    }
    this.maxNodes = maxNodes;
  }

  public void observeServer(String registeredServer) {
    String server = requireServerName(registeredServer);
    synchronized (this.retentionLock) {
      trackLocked(server, this.clock.instant());
      this.observedServers.add(server);
    }
  }

  public java.util.List<String> serverNames() {
    ArrayList<String> names = new ArrayList<>(this.observedServers);
    names.sort(String::compareTo);
    return java.util.List.copyOf(names);
  }

  public BackendNode update(
      String registeredServer,
      BridgeMessage message,
      Instant seenAt) {
    String server = requireServerName(registeredServer);
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(seenAt, "seenAt");
    if (message.platform() == PlatformKind.VELOCITY) {
      throw new IllegalArgumentException("Velocity cannot register as a backend node");
    }
    if (!BridgeProtocol.BACKEND_HELLO.equals(message.type())
        && !BridgeProtocol.STATUS_RESPONSE.equals(message.type())) {
      throw new IllegalArgumentException("Unsupported backend bridge message: " + message.type());
    }

    synchronized (this.retentionLock) {
      trackLocked(server, this.clock.instant());
      this.observedServers.add(server);
      java.util.concurrent.atomic.AtomicBoolean accepted =
          new java.util.concurrent.atomic.AtomicBoolean();
      BackendNode updated = this.nodes.compute(server, (name, previous) -> {
        if (previous != null && seenAt.isBefore(previous.lastSeen())) return previous;
        accepted.set(true);
        Map<String, String> merged = new LinkedHashMap<>();
        if (previous != null) merged.putAll(previous.status());
        merged.putAll(message.attributes());
        return new BackendNode(
            name,
            message.nodeId(),
            message.platform(),
            parseCapabilities(merged.get("capabilities")),
            merged,
            seenAt);
      });
      if (accepted.get()) {
        this.health.computeIfAbsent(server, ignored -> new NodeHealthStateMachine())
            .healthyHeartbeat();
      }
      return updated;
    }
  }

  public Optional<BackendNode> find(String registeredServer) {
    return Optional.ofNullable(this.nodes.get(registeredServer));
  }

  public Collection<BackendNode> all() {
    ArrayList<BackendNode> snapshot = new ArrayList<>(this.nodes.values());
    snapshot.sort(java.util.Comparator.comparing(BackendNode::registeredServer));
    return java.util.List.copyOf(snapshot);
  }

  public NodeHealthStateMachine.Snapshot markHeartbeatMissed(String registeredServer) {
    String server = requireServerName(registeredServer);
    synchronized (this.retentionLock) {
      trackLocked(server, this.clock.instant());
      return this.health.computeIfAbsent(server, ignored -> new NodeHealthStateMachine())
          .missedHeartbeat();
    }
  }

  public NodeHealthStateMachine.Snapshot markHeartbeatHealthy(String registeredServer) {
    String server = requireServerName(registeredServer);
    synchronized (this.retentionLock) {
      trackLocked(server, this.clock.instant());
      return this.health.computeIfAbsent(server, ignored -> new NodeHealthStateMachine())
          .healthyHeartbeat();
    }
  }

  public NodeHealthStateMachine.Snapshot health(String registeredServer) {
    String server = requireServerName(registeredServer);
    synchronized (this.retentionLock) {
      trackLocked(server, this.clock.instant());
      return this.health.computeIfAbsent(server, ignored -> new NodeHealthStateMachine())
          .snapshot();
    }
  }

  public int admissionWeight(String registeredServer) {
    return this.health(registeredServer).admissionWeight();
  }

  public int pruneExpired() {
    synchronized (this.retentionLock) {
      return pruneExpiredLocked(this.clock.instant());
    }
  }

  int trackedNodeCount() {
    return this.lastActivity.size();
  }

  int healthEntryCount() {
    return this.health.size();
  }

  public void clear() {
    synchronized (this.retentionLock) {
      this.nodes.clear();
      this.observedServers.clear();
      this.health.clear();
      this.lastActivity.clear();
    }
  }

  private void trackLocked(String server, Instant activityAt) {
    if (!this.lastActivity.containsKey(server)) {
      pruneExpiredLocked(this.clock.instant());
      if (this.lastActivity.size() >= this.maxNodes) evictOldestLocked();
    }
    this.lastActivity.merge(server, activityAt,
        (previous, candidate) -> candidate.isAfter(previous) ? candidate : previous);
  }

  private int pruneExpiredLocked(Instant now) {
    int removed = 0;
    for (var entry : this.lastActivity.entrySet()) {
      if (entry.getValue().plus(this.offlineTtl).isAfter(now)) continue;
      if (this.lastActivity.remove(entry.getKey(), entry.getValue())) {
        removeNode(entry.getKey());
        removed++;
      }
    }
    return removed;
  }

  private void evictOldestLocked() {
    this.lastActivity.entrySet().stream()
        .min(Map.Entry.comparingByValue())
        .ifPresent(entry -> {
          this.lastActivity.remove(entry.getKey(), entry.getValue());
          removeNode(entry.getKey());
        });
  }

  private void removeNode(String server) {
    this.nodes.remove(server);
    this.observedServers.remove(server);
    this.health.remove(server);
  }

  private static String requireServerName(String registeredServer) {
    if (registeredServer == null || registeredServer.isBlank()) {
      throw new IllegalArgumentException("registeredServer must not be blank");
    }
    return registeredServer;
  }

  private static Set<String> parseCapabilities(String value) {
    if (value == null || value.isBlank()) return Set.of();
    Set<String> capabilities = new TreeSet<>();
    for (String entry : value.split(",")) {
      String capability = entry.trim();
      if (!capability.isEmpty()) capabilities.add(capability);
    }
    return Set.copyOf(capabilities);
  }
}
