package io.github.addxiaoyi.starx.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

final class BackendCommandMailboxTest {

  @Test
  void keepsCommandsIsolatedAndOrderedPerRegisteredServer() {
    BackendCommandMailbox mailbox = new BackendCommandMailbox(2);
    BridgeMessage first = BridgeMessage.statusRequest("proxy", "status-1");
    BridgeMessage second = BridgeMessage.statusRequest("proxy", "status-2");

    assertTrue(mailbox.offer("factions", first));
    assertTrue(mailbox.offer("factions", second));
    assertFalse(mailbox.offer(
        "factions", BridgeMessage.statusRequest("proxy", "status-3")));
    assertTrue(mailbox.poll("lobby").isEmpty());
    assertEquals("status-1", mailbox.poll("factions").orElseThrow().correlationId());
    assertEquals("status-2", mailbox.poll("factions").orElseThrow().correlationId());
    assertTrue(mailbox.poll("factions").isEmpty());
  }

  @Test
  void rejectsBackendMessagesFromTheProxyCommandQueue() {
    BackendCommandMailbox mailbox = new BackendCommandMailbox(1);
    BridgeMessage response = BridgeMessage.statusResponse(
        "factions",
        io.github.addxiaoyi.starx.api.bridge.PlatformKind.PAPER,
        "status-1",
        java.util.Map.of("online", "0"));

    IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> mailbox.offer("factions", response));

    assertTrue(error.getMessage().contains(BridgeProtocol.STATUS_RESPONSE));
  }

  @Test
  void retainsTransportMetricsAfterTheQueueIsDrained() {
    BackendCommandMailbox mailbox = new BackendCommandMailbox(1);
    BridgeMessage command = BridgeMessage.statusRequest("proxy", "status-1");

    assertTrue(mailbox.offer("factions", command));
    assertFalse(mailbox.offer(
        "factions", BridgeMessage.statusRequest("proxy", "status-2")));
    assertEquals("status-1", mailbox.poll("factions").orElseThrow().correlationId());

    BackendCommandMailbox.Snapshot snapshot = mailbox.snapshot("factions");
    assertEquals(1, snapshot.accepted());
    assertEquals(1, snapshot.delivered());
    assertEquals(1, snapshot.rejected());
    assertEquals(0, snapshot.queued());
  }

  @Test
  void expiredCommandsAreRejectedInsteadOfDelivered() {
    MutableClock clock = new MutableClock();
    BackendCommandMailbox mailbox = new BackendCommandMailbox(
        2, clock, Duration.ofSeconds(30));
    mailbox.offer("factions", BridgeMessage.statusRequest("proxy", "status-1"));

    clock.advance(30_001);

    assertTrue(mailbox.poll("factions").isEmpty());
    BackendCommandMailbox.Snapshot snapshot = mailbox.snapshot("factions");
    assertEquals(1, snapshot.accepted());
    assertEquals(0, snapshot.delivered());
    assertEquals(1, snapshot.rejected());
    assertEquals(0, snapshot.queued());
  }

  @Test
  void idleEmptyMailboxesAreReclaimedWithoutDroppingLiveCommands() {
    MutableClock clock = new MutableClock();
    BackendCommandMailbox mailbox = new BackendCommandMailbox(
        1, clock, Duration.ofMinutes(5), Duration.ofMinutes(1), 2);
    BridgeMessage first = BridgeMessage.statusRequest("proxy", "status-1");
    BridgeMessage second = BridgeMessage.statusRequest("proxy", "status-2");

    assertTrue(mailbox.offer("temporary-a", first));
    assertTrue(mailbox.poll("temporary-a").isPresent());
    assertTrue(mailbox.offer("temporary-b", second));
    assertFalse(mailbox.offer(
        "temporary-c", BridgeMessage.statusRequest("proxy", "status-3")));
    assertEquals(2, mailbox.mailboxCount());

    clock.advance(Duration.ofMinutes(1).plusMillis(1).toMillis());

    assertEquals(1, mailbox.pruneIdle());
    assertEquals(1, mailbox.mailboxCount());
    assertEquals("status-2", mailbox.poll("temporary-b").orElseThrow().correlationId());
    assertTrue(mailbox.offer(
        "temporary-c", BridgeMessage.statusRequest("proxy", "status-3")));
    assertEquals(2, mailbox.mailboxCount());
  }

  @Test
  void acceptedCommandsSurviveConcurrentIdlePruning() throws Exception {
    BackendCommandMailbox mailbox = new BackendCommandMailbox(
        1,
        Clock.systemUTC(),
        Duration.ofMinutes(1),
        Duration.ofNanos(1),
        16);
    var executor = Executors.newFixedThreadPool(5);
    var start = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();

    try {
      futures.add(executor.submit(() -> {
        await(start, failure);
        for (int i = 0; i < 20_000 && failure.get() == null; i++) {
          mailbox.pruneIdle();
        }
      }));
      for (int worker = 0; worker < 4; worker++) {
        int workerId = worker;
        futures.add(executor.submit(() -> {
          await(start, failure);
          String server = "worker-" + workerId;
          for (int i = 0; i < 2_000 && failure.get() == null; i++) {
            String correlationId = server + '-' + i;
            BridgeMessage command = BridgeMessage.statusRequest("proxy", correlationId);
            if (!mailbox.offer(server, command)) {
              failure.compareAndSet(null, new AssertionError("offer rejected for " + server));
              break;
            }
            var delivered = mailbox.poll(server);
            if (delivered.isEmpty()
                || !correlationId.equals(delivered.orElseThrow().correlationId())) {
              failure.compareAndSet(null,
                  new AssertionError("accepted command detached for " + server));
              break;
            }
          }
        }));
      }

      start.countDown();
      for (var future : futures) future.get();
      assertNull(failure.get(), () -> "concurrent mailbox failure: " + failure.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void acceptsMaintenanceConfigForAnEmptyBackend() {
    BackendCommandMailbox mailbox = new BackendCommandMailbox(1);
    BridgeMessage command = BridgeMessage.maintenanceConfig("proxy", "maint-1", true);

    assertTrue(mailbox.offer("factions", command));
    BridgeMessage delivered = mailbox.poll("factions").orElseThrow();
    assertEquals(BridgeProtocol.CONFIG_SYNC, delivered.type());
    assertEquals("true", delivered.attributes().get("maintenance"));
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
    private long millis;
    void advance(long amount) { millis += amount; }
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
  }
}
