package io.github.addxiaoyi.starx.velocity.module.uworld;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class UworldDiagnosticsState<P, S, F> {

  private final ConcurrentMap<P, Entry<S, F>> entries = new ConcurrentHashMap<>();

  void begin(P player, S previousServer) {
    this.entries.put(
        Objects.requireNonNull(player, "player"),
        new Entry<>(previousServer, null));
  }

  boolean bind(P player, F flow) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(flow, "flow");
    boolean[] bound = {false};
    this.entries.computeIfPresent(player, (key, current) -> {
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
    Entry<S, F> current = this.entries.get(player);
    return current != null && current.flow() == flow;
  }

  S returnTarget(P player, S fallback) {
    Entry<S, F> current = this.entries.get(Objects.requireNonNull(player, "player"));
    return current == null || current.previousServer() == null
        ? fallback
        : current.previousServer();
  }

  boolean finish(P player, F flow) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(flow, "flow");
    boolean[] removed = {false};
    this.entries.computeIfPresent(player, (key, current) -> {
      if (current.flow() != flow) {
        return current;
      }
      removed[0] = true;
      return null;
    });
    return removed[0];
  }

  void remove(P player) {
    this.entries.remove(Objects.requireNonNull(player, "player"));
  }

  void clear() {
    this.entries.clear();
  }

  private record Entry<S, F>(S previousServer, F flow) {
  }
}
