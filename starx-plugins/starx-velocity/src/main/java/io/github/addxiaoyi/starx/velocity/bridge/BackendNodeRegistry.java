package io.github.addxiaoyi.starx.velocity.bridge;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.common.platform.NodeHealthStateMachine;
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
  private final ConcurrentMap<String, BackendNode> nodes = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, NodeHealthStateMachine> health = new ConcurrentHashMap<>();
  private final Set<String> observedServers = ConcurrentHashMap.newKeySet();

  public void observeServer(String registeredServer) {
    if (registeredServer == null || registeredServer.isBlank()) {
      throw new IllegalArgumentException("registeredServer must not be blank");
    }
    this.observedServers.add(registeredServer);
  }

  public java.util.List<String> serverNames() {
    ArrayList<String> names = new ArrayList<>(this.observedServers);
    names.sort(String::compareTo);
    return java.util.List.copyOf(names);
  }

  public BackendNode update(
      String registeredServer,
      BridgeMessage message,
      Instant seenAt
  ) {
    if (registeredServer == null || registeredServer.isBlank()) {
      throw new IllegalArgumentException("registeredServer must not be blank");
    }
    this.observedServers.add(registeredServer);
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(seenAt, "seenAt");
    if (message.platform() == PlatformKind.VELOCITY) {
      throw new IllegalArgumentException("Velocity cannot register as a backend node");
    }
    if (!BridgeProtocol.BACKEND_HELLO.equals(message.type())
        && !BridgeProtocol.STATUS_RESPONSE.equals(message.type())) {
      throw new IllegalArgumentException("Unsupported backend bridge message: " + message.type());
    }

    java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean();
    BackendNode updated = this.nodes.compute(registeredServer, (name, previous) -> {
      if (previous != null && seenAt.isBefore(previous.lastSeen())) {
        return previous;
      }
      accepted.set(true);
      Map<String, String> merged = new LinkedHashMap<>();
      if (previous != null) {
        merged.putAll(previous.status());
      }
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
      this.health.computeIfAbsent(
          registeredServer, ignored -> new NodeHealthStateMachine()).healthyHeartbeat();
    }
    return updated;
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
    return this.health.computeIfAbsent(registeredServer, ignored -> new NodeHealthStateMachine())
        .missedHeartbeat();
  }

  public NodeHealthStateMachine.Snapshot markHeartbeatHealthy(String registeredServer) {
    return this.health.computeIfAbsent(registeredServer, ignored -> new NodeHealthStateMachine())
        .healthyHeartbeat();
  }

  public NodeHealthStateMachine.Snapshot health(String registeredServer) {
    return this.health.computeIfAbsent(registeredServer, ignored -> new NodeHealthStateMachine())
        .snapshot();
  }

  public int admissionWeight(String registeredServer) {
    return this.health(registeredServer).admissionWeight();
  }

  public void clear() {
    this.nodes.clear();
    this.observedServers.clear();
    this.health.clear();
  }

  private static Set<String> parseCapabilities(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    Set<String> capabilities = new TreeSet<>();
    for (String entry : value.split(",")) {
      String capability = entry.trim();
      if (!capability.isEmpty()) {
        capabilities.add(capability);
      }
    }
    return Set.copyOf(capabilities);
  }
}
