package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import java.util.Objects;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class CredentialChangeDisconnectService implements AutoCloseable {
  static final String EVENT_TYPE = "player:credentials:changed";
  static final String SECURITY_EVENT_TYPE = "player:security:changed";

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
    this.events.subscribe(SECURITY_EVENT_TYPE, this.subscriber);
  }

  private void onChanged(StarxEvent event) {
    if (this.closed.get()) return;
    List<UUID> playerIds = event.type().equals(SECURITY_EVENT_TYPE)
        ? securityRevokedPlayerIds(event)
        : singlePlayerId(event.payload().get("uuid"));
    if (playerIds.isEmpty()) return;
    this.scheduler.accept(() -> {
      if (this.closed.get()) return;
      playerIds.forEach(this.disconnect);
    });
  }

  private static List<UUID> singlePlayerId(Object raw) {
    UUID playerId = playerId(raw);
    return playerId == null ? List.of() : List.of(playerId);
  }

  private static List<UUID> playerIds(Object raw) {
    if (!(raw instanceof Collection<?> values)) return List.of();
    return values.stream().map(CredentialChangeDisconnectService::playerId)
        .filter(Objects::nonNull).distinct().toList();
  }

  private static List<UUID> securityRevokedPlayerIds(StarxEvent event) {
    return Boolean.TRUE.equals(event.payload().get("disconnectSessions"))
        ? playerIds(event.payload().get("revokedSessionUuids"))
        : List.of();
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
    this.events.unsubscribe(SECURITY_EVENT_TYPE, this.subscriber);
  }
}
