package io.github.addxiaoyi.starx.velocity.module.tab;

import com.velocitypowered.api.proxy.server.ServerPing;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class ServerMetadataCache {
  record Snapshot(boolean known, boolean online, int maxPlayers) {
    private static Snapshot unknown() {
      return new Snapshot(false, false, -1);
    }
  }

  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final long ttlNanos;
  private final long timeoutSeconds;
  private final LongSupplier clock;

  ServerMetadataCache(Duration ttl, Duration timeout) {
    this(ttl, timeout, System::nanoTime);
  }

  ServerMetadataCache(Duration ttl, Duration timeout, LongSupplier clock) {
    this.ttlNanos = positiveNanos(ttl, "ttl");
    this.timeoutSeconds = Math.max(1, Objects.requireNonNull(timeout, "timeout").toSeconds());
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  Snapshot snapshot(String serverName) {
    Entry entry = this.entries.get(Objects.requireNonNull(serverName, "serverName"));
    return entry == null ? Snapshot.unknown() : entry.snapshot;
  }

  void clear() {
    this.entries.clear();
  }

  void refreshIfDue(
      String serverName,
      Supplier<CompletableFuture<ServerPing>> ping,
      Runnable onChange
  ) {
    Entry entry = this.entries.computeIfAbsent(
        Objects.requireNonNull(serverName, "serverName"), ignored -> new Entry());
    long now = this.clock.getAsLong();
    if (entry.refreshedAtNanos != Long.MIN_VALUE
        && now - entry.refreshedAtNanos < this.ttlNanos
        || !entry.inFlight.compareAndSet(false, true)) {
      return;
    }
    CompletableFuture<ServerPing> request;
    try {
      request = Objects.requireNonNull(ping.get(), "ping returned null");
    } catch (RuntimeException error) {
      complete(entry, null, error, onChange);
      return;
    }
    request.orTimeout(this.timeoutSeconds, TimeUnit.SECONDS)
        .whenComplete((result, error) -> complete(entry, result, error, onChange));
  }

  private void complete(Entry entry, ServerPing ping, Throwable error, Runnable onChange) {
    Snapshot previous = entry.snapshot;
    Snapshot updated = error == null && ping != null
        ? new Snapshot(true, true, ping.getPlayers().map(ServerPing.Players::getMax).orElse(-1))
        : new Snapshot(true, false, -1);
    entry.snapshot = updated;
    entry.refreshedAtNanos = this.clock.getAsLong();
    entry.inFlight.set(false);
    if (!updated.equals(previous)) {
      onChange.run();
    }
  }

  private static long positiveNanos(Duration duration, String name) {
    long nanos = Objects.requireNonNull(duration, name).toNanos();
    if (nanos < 1) throw new IllegalArgumentException(name + " must be positive");
    return nanos;
  }

  private static final class Entry {
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile long refreshedAtNanos = Long.MIN_VALUE;
    private volatile Snapshot snapshot = Snapshot.unknown();
  }
}
