package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class ReconnectTargetStore {
  private final int capacity;
  private final Map<UUID, Target> targets = new HashMap<>();

  ReconnectTargetStore(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
  }

  synchronized void remember(UUID playerId, String server, long now) {
    Objects.requireNonNull(playerId, "playerId");
    String name = Objects.requireNonNull(server, "server").trim();
    if (name.isEmpty()) throw new IllegalArgumentException("server must not be blank");
    this.targets.put(playerId, new Target(name, now));
    while (this.targets.size() > this.capacity) {
      this.targets.entrySet().stream()
          .min(java.util.Comparator.comparingLong(entry -> entry.getValue().rememberedAt()))
          .ifPresent(oldest -> this.targets.remove(oldest.getKey()));
    }
  }

  synchronized Optional<String> peek(UUID playerId) {
    Target target = this.targets.get(playerId);
    return target == null ? Optional.empty() : Optional.of(target.server());
  }

  synchronized Optional<String> consume(UUID playerId) {
    Target target = this.targets.remove(playerId);
    return target == null ? Optional.empty() : Optional.of(target.server());
  }

  synchronized int size() { return this.targets.size(); }
  synchronized void clear() { this.targets.clear(); }

  private record Target(String server, long rememberedAt) { }
}
