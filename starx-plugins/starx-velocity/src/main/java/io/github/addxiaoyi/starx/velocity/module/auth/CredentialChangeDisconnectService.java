package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class CredentialChangeDisconnectService implements AutoCloseable {
  static final String EVENT_TYPE = "player:credentials:changed";

  private final EventBus events;
  private final Consumer<Runnable> scheduler;
  private final Consumer<UUID> disconnect;
  private final Consumer<StarxEvent> subscriber = this::onChanged;
  private final AtomicBoolean closed = new AtomicBoolean();

  public CredentialChangeDisconnectService(
      EventBus events, Consumer<Runnable> scheduler, Consumer<UUID> disconnect) {
    this.events = Objects.requireNonNull(events, "events");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.disconnect = Objects.requireNonNull(disconnect, "disconnect");
    this.events.subscribe(EVENT_TYPE, this.subscriber);
  }

  private void onChanged(StarxEvent event) {
    if (this.closed.get()) return;
    UUID playerId = playerId(event.payload().get("uuid"));
    if (playerId == null) return;
    this.scheduler.accept(() -> {
      if (!this.closed.get()) this.disconnect.accept(playerId);
    });
  }

  private static UUID playerId(Object raw) {
    if (raw instanceof UUID uuid) return uuid;
    if (!(raw instanceof String text)) return null;
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  @Override
  public void close() {
    if (!this.closed.compareAndSet(false, true)) return;
    this.events.unsubscribe(EVENT_TYPE, this.subscriber);
  }
}
