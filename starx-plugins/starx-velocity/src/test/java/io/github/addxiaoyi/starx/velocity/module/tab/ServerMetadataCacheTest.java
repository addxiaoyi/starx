package io.github.addxiaoyi.starx.velocity.module.tab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.server.ServerPing;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class ServerMetadataCacheTest {
  private static final Duration TTL = Duration.ofSeconds(30);

  @Test
  void cachesCapacityUntilTtlExpiresAndReplacesItWhenServerCapacityChanges() {
    AtomicLong now = new AtomicLong(1);
    AtomicInteger pings = new AtomicInteger();
    AtomicInteger changes = new AtomicInteger();
    ServerMetadataCache cache = cache(now);

    refresh(cache, "survival", ping(pings, 80), changes);
    assertEquals(new ServerMetadataCache.Snapshot(true, true, 80), cache.snapshot("survival"));
    now.addAndGet(TTL.toNanos() - 1);
    refresh(cache, "survival", ping(pings, 100), changes);
    assertEquals(1, pings.get());
    assertEquals(1, changes.get());

    now.incrementAndGet();
    refresh(cache, "survival", ping(pings, 100), changes);
    assertEquals(new ServerMetadataCache.Snapshot(true, true, 100), cache.snapshot("survival"));
    assertEquals(2, pings.get());
    assertEquals(2, changes.get());
  }

  @Test
  void recordsOfflineAndOnlineTransitionsAndNotifiesOnlyOnStateChanges() {
    AtomicLong now = new AtomicLong(1);
    AtomicInteger changes = new AtomicInteger();
    ServerMetadataCache cache = cache(now);

    refresh(cache, "lobby", () -> CompletableFuture.failedFuture(
        new IllegalStateException("offline")), changes);
    assertTrue(cache.snapshot("lobby").known());
    assertFalse(cache.snapshot("lobby").online());
    assertEquals(1, changes.get());

    now.addAndGet(TTL.toNanos());
    refresh(cache, "lobby", () -> CompletableFuture.completedFuture(ping(60)), changes);
    assertEquals(new ServerMetadataCache.Snapshot(true, true, 60), cache.snapshot("lobby"));
    assertEquals(2, changes.get());

    now.addAndGet(TTL.toNanos());
    refresh(cache, "lobby", () -> CompletableFuture.completedFuture(ping(60)), changes);
    assertEquals(2, changes.get());
  }

  @Test
  void coalescesConcurrentPingRequests() {
    AtomicLong now = new AtomicLong(1);
    AtomicInteger pings = new AtomicInteger();
    AtomicInteger changes = new AtomicInteger();
    CompletableFuture<ServerPing> pending = new CompletableFuture<>();
    ServerMetadataCache cache = cache(now);

    cache.refreshIfDue("minigame", () -> {
      pings.incrementAndGet();
      return pending;
    }, changes::incrementAndGet);
    cache.refreshIfDue("minigame", () -> {
      pings.incrementAndGet();
      return CompletableFuture.completedFuture(ping(20));
    }, changes::incrementAndGet);

    assertEquals(1, pings.get());
    assertFalse(cache.snapshot("minigame").known());
    pending.complete(ping(32));
    assertEquals(new ServerMetadataCache.Snapshot(true, true, 32), cache.snapshot("minigame"));
    assertEquals(1, changes.get());
  }

  private static ServerMetadataCache cache(AtomicLong now) {
    return new ServerMetadataCache(TTL, Duration.ofSeconds(2), now::get);
  }

  private static void refresh(
      ServerMetadataCache cache,
      String name,
      java.util.function.Supplier<CompletableFuture<ServerPing>> request,
      AtomicInteger changes
  ) {
    cache.refreshIfDue(name, request, changes::incrementAndGet);
  }

  private static java.util.function.Supplier<CompletableFuture<ServerPing>> ping(
      AtomicInteger requests,
      int capacity
  ) {
    return () -> {
      requests.incrementAndGet();
      return CompletableFuture.completedFuture(ping(capacity));
    };
  }

  private static ServerPing ping(int capacity) {
    return ServerPing.builder()
        .version(new ServerPing.Version(774, "1.21.11"))
        .onlinePlayers(0)
        .maximumPlayers(capacity)
        .description(Component.empty())
        .build();
  }
}
