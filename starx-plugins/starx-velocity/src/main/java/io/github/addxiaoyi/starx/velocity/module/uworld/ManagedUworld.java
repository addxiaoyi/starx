package io.github.addxiaoyi.starx.velocity.module.uworld;

import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.Limbo;
import io.github.addxiaoyi.starx.uworld.UworldEnterResult;
import io.github.addxiaoyi.starx.uworld.UworldFlowHandler;
import io.github.addxiaoyi.starx.uworld.UworldFlowOptions;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;

final class ManagedUworld implements UworldHandle {

  private final EmbeddedUworldRuntime runtime;
  private final String owner;
  private final UworldSpec spec;
  private final Limbo limbo;
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final AtomicBoolean disposed = new AtomicBoolean();
  private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

  ManagedUworld(
      EmbeddedUworldRuntime runtime,
      String owner,
      UworldSpec spec,
      Limbo limbo
  ) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.spec = Objects.requireNonNull(spec, "spec");
    this.limbo = Objects.requireNonNull(limbo, "limbo");
  }

  @Override
  public String name() {
    return this.spec.name();
  }

  String owner() {
    return this.owner;
  }

  UworldSpec spec() {
    return this.spec;
  }

  Limbo limbo() {
    return this.limbo;
  }

  @Override
  public boolean isOpen() {
    return this.open.get();
  }

  @Override
  public UworldEnterResult enter(
      Player player,
      UworldFlowOptions options,
      UworldFlowHandler handler
  ) {
    return this.runtime.enter(this, player, options, handler);
  }

  @Override
  public CompletionStage<Void> closeAsync(Component reason) {
    return this.close(reason, UworldOutcomeType.WORLD_CLOSED);
  }

  CompletionStage<Void> closeForRuntime(Component reason) {
    return this.close(reason, UworldOutcomeType.RUNTIME_STOPPING);
  }

  private CompletionStage<Void> close(Component reason, UworldOutcomeType outcomeType) {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(outcomeType, "outcomeType");
    while (true) {
      CompletableFuture<Void> existing = this.closeFuture.get();
      if (existing != null) {
        return existing;
      }
      CompletableFuture<Void> closing = new CompletableFuture<>();
      if (!this.closeFuture.compareAndSet(null, closing)) {
        continue;
      }
      try {
        this.runtime.closeWorld(this, reason, outcomeType).whenComplete((ignored, error) ->
            this.finishClose(closing, error));
      } catch (RuntimeException error) {
        this.finishClose(closing, error);
      }
      return closing;
    }
  }

  boolean markClosed() {
    return this.open.compareAndSet(true, false);
  }

  synchronized void dispose() {
    if (this.disposed.get()) {
      return;
    }
    this.limbo.dispose();
    this.disposed.set(true);
  }

  private void finishClose(CompletableFuture<Void> closing, Throwable error) {
    if (error == null) {
      closing.complete(null);
      return;
    }
    this.closeFuture.compareAndSet(closing, null);
    closing.completeExceptionally(error);
  }
}
