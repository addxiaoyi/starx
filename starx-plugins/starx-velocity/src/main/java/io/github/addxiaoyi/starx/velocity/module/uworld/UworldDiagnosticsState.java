package io.github.addxiaoyi.starx.velocity.module.uworld;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class UworldDiagnosticsState<P, S, F> {

  private final ConcurrentMap<IdentityKey<P>, Entry<S, F>> entries = new ConcurrentHashMap<>();

  void begin(P player, S previousServer) {
    P checkedPlayer = Objects.requireNonNull(player, "player");
    this.entries.put(
        new IdentityKey<>(checkedPlayer),
        new Entry<>(previousServer, null));
  }

  boolean bind(P player, F flow) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(flow, "flow");
    boolean[] bound = {false};
    this.entries.computeIfPresent(new IdentityKey<>(player), (key, current) -> {
      if (current.flow() != null) {
        return current;
      }
      bound[0] = true;
      return new Entry<>(current.previousServer(), flow);
    });
    return bound[0];
  }

  boolean owns(P player, F flow) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(flow, "flow");
    Entry<S, F> current = this.entries.get(new IdentityKey<>(player));
    return current != null && current.flow() == flow;
  }

  S returnTarget(P player, S fallback) {
    Entry<S, F> current = this.entries.get(new IdentityKey<>(
        Objects.requireNonNull(player, "player")));
    return current == null || current.previousServer() == null
        ? fallback
        : current.previousServer();
  }

  boolean finish(P player, F flow) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(flow, "flow");
    boolean[] removed = {false};
    this.entries.computeIfPresent(new IdentityKey<>(player), (key, current) -> {
      if (current.flow() != flow) {
        return current;
      }
      removed[0] = true;
      return null;
    });
    return removed[0];
  }

  void remove(P player) {
    this.entries.remove(new IdentityKey<>(Objects.requireNonNull(player, "player")));
  }

  void clear() {
    this.entries.clear();
  }

  private record Entry<S, F>(S previousServer, F flow) {
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
