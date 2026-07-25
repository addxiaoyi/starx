package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class MaintenanceRuntimeState {
  private final AtomicBoolean enabled = new AtomicBoolean();
  private final Consumer<Boolean> broadcaster;

  MaintenanceRuntimeState(Consumer<Boolean> broadcaster) {
    this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster");
  }

  void restore(boolean restored) {
    this.enabled.set(restored);
    this.broadcaster.accept(restored);
  }

  boolean change(boolean next) {
    if (!this.enabled.compareAndSet(!next, next)) {
      return false;
    }
    this.broadcaster.accept(next);
    return true;
  }

  boolean enabled() {
    return this.enabled.get();
  }

  void rebroadcast() {
    this.broadcaster.accept(this.enabled.get());
  }
}
