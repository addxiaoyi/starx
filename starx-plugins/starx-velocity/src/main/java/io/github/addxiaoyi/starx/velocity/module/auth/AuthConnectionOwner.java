package io.github.addxiaoyi.starx.velocity.module.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class AuthConnectionOwner<P> {

  private final ConcurrentMap<UUID, P> owners = new ConcurrentHashMap<>();

  boolean claim(UUID playerId, P connection) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(connection, "connection");
    return this.owners.putIfAbsent(playerId, connection) == null;
  }

  boolean isOwner(UUID playerId, P connection) {
    return this.owners.get(playerId) == connection;
  }

  boolean release(UUID playerId, P connection) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(connection, "connection");
    P current = this.owners.get(playerId);
    return current == connection && this.owners.remove(playerId, current);
  }

  Optional<P> owner(UUID playerId) {
    return Optional.ofNullable(this.owners.get(playerId));
  }

  void clear() {
    this.owners.clear();
  }
}
