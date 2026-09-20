package io.github.addxiaoyi.starx.velocity.module.uworld;

import io.github.addxiaoyi.starx.uworld.UworldCreationException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class UworldRegistry<P, W, S> {

  enum ClaimResult {
    ACCEPTED,
    PLAYER_BUSY,
    RUNTIME_STOPPING
  }

  private final ConcurrentMap<String, OwnedWorld<W>> worlds = new ConcurrentHashMap<>();
  private final ConcurrentMap<IdentityKey<P>, S> sessions = new ConcurrentHashMap<>();
  private final AtomicBoolean stopping = new AtomicBoolean();

  boolean registerWorld(String owner, String name, W world) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(world, "world");
    if (this.stopping.get()) {
      throw new UworldCreationException(owner, name, "runtime is stopping");
    }

    OwnedWorld<W> added = new OwnedWorld<>(owner, world);
    OwnedWorld<W> existing = this.worlds.putIfAbsent(name, added);
    if (existing != null) {
      throw new UworldCreationException(
          owner,
          name,
          "already owned by " + existing.owner() + "; requested by " + owner);
    }
    if (this.stopping.get() && this.worlds.remove(name, added)) {
      throw new UworldCreationException(owner, name, "runtime started stopping during creation");
    }
    return true;
  }

  boolean removeWorld(String name, W world) {
    OwnedWorld<W> current = this.worlds.get(name);
    return current != null && current.world() == world && this.worlds.remove(name, current);
  }

  Optional<W> world(String name) {
    OwnedWorld<W> current = this.worlds.get(name);
    return current == null ? Optional.empty() : Optional.of(current.world());
  }

  ClaimResult claim(P player, S session) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(session, "session");
    if (this.stopping.get()) {
      return ClaimResult.RUNTIME_STOPPING;
    }
    if (this.sessions.putIfAbsent(new IdentityKey<>(player), session) != null) {
      return ClaimResult.PLAYER_BUSY;
    }
    if (this.stopping.get() && this.sessions.remove(new IdentityKey<>(player), session)) {
      return ClaimResult.RUNTIME_STOPPING;
    }
    return ClaimResult.ACCEPTED;
  }

  Optional<S> session(P player) {
    return Optional.ofNullable(this.sessions.get(new IdentityKey<>(player)));
  }

  boolean release(P player, S session) {
    return this.sessions.remove(new IdentityKey<>(player), session);
  }

  void beginStopping() {
    this.stopping.set(true);
  }

  boolean isStopping() {
    return this.stopping.get();
  }

  int worldCount() {
    return this.worlds.size();
  }

  int sessionCount() {
    return this.sessions.size();
  }

  Collection<W> worlds() {
    return this.worlds.values().stream().map(OwnedWorld::world).toList();
  }

  Collection<S> sessions() {
    return List.copyOf(this.sessions.values());
  }

  private record OwnedWorld<W>(String owner, W world) {
  }

  private static final class IdentityKey<P> {
    private final P value;
    private final int hash;

    private IdentityKey(P value) {
      this.value = Objects.requireNonNull(value, "value");
      this.hash = System.identityHashCode(value);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof IdentityKey<?> key && this.value == key.value;
    }

    @Override
    public int hashCode() {
      return this.hash;
    }
  }
}
