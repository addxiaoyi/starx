package io.github.addxiaoyi.starx.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BackendNodeRegistryTest {

  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

  @Test
  void storesStatusUnderVelocityRegisteredServerName() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    BridgeMessage status = BridgeMessage.statusResponse(
        "declared-lobby",
        PlatformKind.PAPER,
        "request-1",
        Map.of(
            "online", "3",
            "max", "100",
            "capabilities", "server.status,scheduler.main"));

    registry.update("lobby", status, NOW);

    BackendNode node = registry.find("lobby").orElseThrow();
    assertEquals("lobby", node.registeredServer());
    assertEquals("declared-lobby", node.declaredNodeId());
    assertEquals(3, node.onlinePlayers());
    assertEquals(100, node.maxPlayers());
    assertTrue(node.capabilities().contains("scheduler.main"));
    assertTrue(registry.find("declared-lobby").isEmpty());
  }

  @Test
  void helloAfterStatusDoesNotEraseMetrics() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update("lobby", BridgeMessage.statusResponse(
        "lobby", PlatformKind.FOLIA, "request-1", Map.of("online", "5")), NOW);
    registry.update("lobby", new BridgeMessage(
        BridgeProtocol.BACKEND_HELLO,
        "lobby",
        PlatformKind.FOLIA,
        "",
        Map.of("capabilities", "scheduler.region")), NOW.plusSeconds(1));

    BackendNode node = registry.find("lobby").orElseThrow();
    assertEquals(5, node.onlinePlayers());
    assertTrue(node.capabilities().contains("scheduler.region"));
    assertEquals(NOW.plusSeconds(1), node.lastSeen());
  }

  @Test
  void rejectsVelocityAndUnknownMessageTypes() {
    BackendNodeRegistry registry = new BackendNodeRegistry();

    assertThrows(IllegalArgumentException.class, () -> registry.update(
        "lobby", BridgeMessage.hello("proxy", PlatformKind.VELOCITY), NOW));
    assertThrows(IllegalArgumentException.class, () -> registry.update(
        "lobby",
        new BridgeMessage("backend.unknown", "lobby", PlatformKind.PAPER, "", Map.of()),
        NOW));
  }

  @Test
  void marksOldSnapshotsAsStaleWithoutDeletingEvidence() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update("lobby", BridgeMessage.statusResponse(
        "lobby", PlatformKind.PAPER, "request-1", Map.of()), NOW);

    BackendNode node = registry.find("lobby").orElseThrow();
    assertFalse(node.isStale(NOW.plus(Duration.ofMinutes(4)), Duration.ofMinutes(5)));
    assertTrue(node.isStale(NOW.plus(Duration.ofMinutes(6)), Duration.ofMinutes(5)));
    assertEquals(1, registry.all().size());
  }

  @Test
  void tracksConnectedServerNamesWithoutEnumeratingProxyConfiguration() {
    BackendNodeRegistry registry = new BackendNodeRegistry();

    registry.observeServer("lobby");
    registry.observeServer("lobby");
    registry.observeServer("survival");

    assertEquals(List.of("lobby", "survival"), registry.serverNames());
  }

  @Test
  void offlineNodesAreRemovedFromEveryRegistryIndex() {
    MutableClock clock = new MutableClock(NOW);
    BackendNodeRegistry registry = new BackendNodeRegistry(
        clock, Duration.ofMinutes(5), 10);
    registry.observeServer("temporary");
    registry.update("temporary", BridgeMessage.statusResponse(
        "temporary", PlatformKind.PAPER, "request-1", Map.of()), NOW);
    registry.markHeartbeatHealthy("temporary");

    clock.advance(Duration.ofMinutes(5).plusSeconds(1));

    assertEquals(1, registry.pruneExpired());
    assertTrue(registry.serverNames().isEmpty());
    assertTrue(registry.find("temporary").isEmpty());
    assertEquals(0, registry.trackedNodeCount());
    assertEquals(0, registry.healthEntryCount());
  }

  @Test
  void capacityLimitEvictsTheOldestObservedServer() {
    MutableClock clock = new MutableClock(NOW);
    BackendNodeRegistry registry = new BackendNodeRegistry(
        clock, Duration.ofHours(1), 2);
    registry.observeServer("temporary-a");
    clock.advance(Duration.ofSeconds(1));
    registry.observeServer("temporary-b");
    clock.advance(Duration.ofSeconds(1));
    registry.observeServer("temporary-c");

    assertEquals(List.of("temporary-b", "temporary-c"), registry.serverNames());
    assertEquals(2, registry.trackedNodeCount());
  }

  @Test
  void concurrentCapacityTrackingLeavesNoOrphanedIndexes() throws Exception {
    BackendNodeRegistry registry = new BackendNodeRegistry(
        Clock.systemUTC(), Duration.ofHours(1), 16);
    var executor = Executors.newFixedThreadPool(8);
    var start = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();

    try {
      for (int worker = 0; worker < 8; worker++) {
        int workerId = worker;
        futures.add(executor.submit(() -> {
          await(start, failure);
          for (int i = 0; i < 200 && failure.get() == null; i++) {
            String server = "dynamic-" + workerId + '-' + i;
            Instant seenAt = Instant.now();
            registry.update(server, BridgeMessage.statusResponse(
                server, PlatformKind.PAPER, server, Map.of("online", "0")), seenAt);
            registry.markHeartbeatHealthy(server);
          }
        }));
      }

      start.countDown();
      for (var future : futures) future.get();
      assertNull(failure.get(), () -> "concurrent registry failure: " + failure.get());
      assertTrue(registry.trackedNodeCount() <= 16);
      assertTrue(registry.serverNames().size() <= 16);
      assertTrue(registry.all().size() <= 16);
      assertTrue(registry.healthEntryCount() <= 16);
      assertTrue(registry.serverNames().containsAll(
          registry.all().stream().map(BackendNode::registeredServer).toList()));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void ignoresOutOfOrderHeartbeatResponses() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    registry.update("lobby", BridgeMessage.statusResponse(
        "lobby", PlatformKind.PAPER, "new", Map.of("online", "8")), NOW.plusSeconds(10));
    registry.markHeartbeatMissed("lobby");
    int weightBeforeStaleResponse = registry.admissionWeight("lobby");

    BackendNode node = registry.update("lobby", BridgeMessage.statusResponse(
        "lobby", PlatformKind.PAPER, "old", Map.of("online", "1")), NOW);

    assertEquals(8, node.onlinePlayers());
    assertEquals(NOW.plusSeconds(10), node.lastSeen());
    assertEquals(weightBeforeStaleResponse, registry.admissionWeight("lobby"));
  }

  private static void await(CountDownLatch start, AtomicReference<Throwable> failure) {
    try {
      start.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failure.compareAndSet(null, interrupted);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    private void advance(Duration duration) {
      this.current = this.current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return this.current;
    }
  }
}
