package io.github.addxiaoyi.starx.server;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class BackendMaintenanceState {
  private final AtomicBoolean enabled;
  private final Consumer<Boolean> persistence;

  BackendMaintenanceState(boolean restored, Consumer<Boolean> persistence) {
    this.enabled = new AtomicBoolean(restored);
    this.persistence = Objects.requireNonNull(persistence, "persistence");
  }

  boolean update(boolean next) {
    if (!this.enabled.compareAndSet(!next, next)) {
      return false;
    }
    this.persistence.accept(next);
    return true;
  }

  boolean enabled() {
    return this.enabled.get();
  }
}
