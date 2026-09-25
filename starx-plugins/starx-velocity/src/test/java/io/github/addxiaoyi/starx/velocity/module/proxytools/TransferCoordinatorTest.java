package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.*;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class TransferCoordinatorTest {
  private final RegisteredServer lobby = server("lobby");

  @Test
  void rejectsDuplicateAcrossDifferentEntryPoints() {
    try (var transfers = coordinator()) {
      Player player = player(UUID.randomUUID(), CompletableFuture::new);
      var request = transfers.transfer(player, this.lobby, "queue").toCompletableFuture();
      var duplicate = transfers.transfer(player, this.lobby, "hub").toCompletableFuture().join();
      assertEquals(TransferCoordinator.Status.DUPLICATE, duplicate.status());
      assertFalse(request.isDone());
      assertEquals(1, transfers.snapshot().duplicate());
      assertEquals(1, transfers.activeCount());
    }
  }

  @Test
  void cancellationWinsBeforeTransportCallbacksAndIsCountedOnce() {
    try (var transfers = coordinator()) {
      var connection = new CompletableFuture<ConnectionRequestBuilder.Result>();
      UUID id = UUID.randomUUID();
      var request = transfers.transfer(player(id, () -> connection), this.lobby, "hub").toCompletableFuture();
      assertTrue(transfers.cancel(id));
      assertEquals(TransferCoordinator.Status.CANCELLED, request.join().status());
      assertTrue(connection.isCancelled());
      assertFalse(transfers.cancel(id));
      assertEquals(1, transfers.snapshot().cancelled());
      assertEquals(0, transfers.snapshot().failed());
      assertEquals(0, transfers.activeCount());
    }
  }

  @Test
  void recordsBothSynchronousAndAsynchronousFailuresAndReleasesSlots() {
    try (var transfers = coordinator()) {
      var connection = new CompletableFuture<ConnectionRequestBuilder.Result>();
      var request = transfers.transfer(player(UUID.randomUUID(), () -> connection), this.lobby, "queue");
      connection.completeExceptionally(new IllegalStateException("backend closed"));
      assertEquals(TransferCoordinator.Status.FAILURE, request.toCompletableFuture().join().status());
      var immediate = transfers.transfer(player(UUID.randomUUID(), () -> {
        throw new IllegalStateException("connect failed");
      }), this.lobby, "hub").toCompletableFuture().join();
      assertEquals(TransferCoordinator.Status.FAILURE, immediate.status());
      assertEquals(2, transfers.snapshot().failed());
      assertEquals(0, transfers.activeCount());
    }
  }

  @Test
  void timeoutCancelsOriginalFutureAndRecordsOneFailure() throws Exception {
    try (var transfers = new TransferCoordinator(Duration.ofMillis(20))) {
      var connection = new CompletableFuture<ConnectionRequestBuilder.Result>();
      var request = transfers.transfer(player(UUID.randomUUID(), () -> connection), this.lobby, "hub");
      var outcome = request.toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(TransferCoordinator.Status.FAILURE, outcome.status());
      assertInstanceOf(TimeoutException.class, outcome.error());
      assertTrue(connection.isCancelled());
      assertEquals(1, transfers.snapshot().failed());
      assertEquals(0, transfers.snapshot().cancelled());
      assertEquals(0, transfers.activeCount());
    }
  }

  @Test
  void lateConnectionCreationCannotReleaseTheReplacementRequest() throws Exception {
    try (var transfers = coordinator(); var workers = Executors.newFixedThreadPool(1)) {
      UUID id = UUID.randomUUID();
      CountDownLatch creating = new CountDownLatch(1);
      CountDownLatch resume = new CountDownLatch(1);
      var oldTransport = new CompletableFuture<ConnectionRequestBuilder.Result>();
      var oldRequest = workers.submit(() -> transfers.transfer(player(id, () -> {
        creating.countDown();
        await(resume);
        return oldTransport;
      }), this.lobby, "queue"));
      try {
        assertTrue(creating.await(5, TimeUnit.SECONDS));
        assertTrue(transfers.cancel(id));
        var replacement = transfers.transfer(player(id, CompletableFuture::new), this.lobby, "hub");
        resume.countDown();
        assertEquals(TransferCoordinator.Status.CANCELLED,
            oldRequest.get(5, TimeUnit.SECONDS).toCompletableFuture().join().status());
        assertTrue(oldTransport.isCancelled());
        assertFalse(replacement.toCompletableFuture().isDone());
        for (int i = 0; i < 31; i++) {
          assertFalse(transfers.transfer(player(UUID.randomUUID(), CompletableFuture::new), this.lobby, "queue")
              .toCompletableFuture().isDone());
        }
        assertEquals("target-busy", transfers.transfer(player(UUID.randomUUID(), CompletableFuture::new),
            this.lobby, "admin-send").toCompletableFuture().join().reason());
        assertEquals(32, transfers.activeCount());
      } finally {
        resume.countDown();
      }
    }
  }

  @Test
  void concurrentEntrantsRespectNodeLimitAndCanRefillAllReleasedSlots() throws Exception {
    try (var transfers = coordinator(); var workers = Executors.newFixedThreadPool(16)) {
      AtomicInteger connects = new AtomicInteger();
      List<java.util.concurrent.Future<?>> submissions = new ArrayList<>();
      for (int i = 0; i < 256; i++) {
        submissions.add(workers.submit(() -> transfers.transfer(player(UUID.randomUUID(), () -> {
          connects.incrementAndGet();
          return new CompletableFuture<>();
        }), this.lobby, "queue")));
      }
      for (var submission : submissions) submission.get(10, TimeUnit.SECONDS);
      assertEquals(32, connects.get());
      assertEquals(32, transfers.activeCount());
      assertEquals(224, transfers.snapshot().rejected());
      transfers.clear();
      assertEquals(32, transfers.snapshot().cancelled());
      for (int i = 0; i < 32; i++) {
        assertFalse(transfers.transfer(player(UUID.randomUUID(), CompletableFuture::new), this.lobby, "hub")
            .toCompletableFuture().isDone());
      }
      assertEquals(32, transfers.activeCount());
    }
  }

  @Test
  void oldDisconnectDoesNotCancelReconnectedPlayerWithSameUuid() {
    try (var transfers = coordinator()) {
      UUID id = UUID.randomUUID();
      Player oldPlayer = player(id, CompletableFuture::new);
      Player newPlayer = player(id, CompletableFuture::new);
      transfers.transfer(oldPlayer, this.lobby, "hub");
      transfers.cancel(id);
      var request = transfers.transfer(newPlayer, this.lobby, "queue").toCompletableFuture();
      transfers.onDisconnect(new DisconnectEvent(oldPlayer, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
      assertFalse(request.isDone());
      transfers.onDisconnect(new DisconnectEvent(newPlayer, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
      assertEquals(TransferCoordinator.Status.CANCELLED, request.join().status());
      assertEquals(2, transfers.snapshot().cancelled());
    }
  }

  @Test
  void scopedCancellationAndCloseDoNotCrossOwnersOrAcceptNewConnections() {
    var transfers = coordinator();
    UUID hubId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    var hub = transfers.transfer(player(hubId, CompletableFuture::new), this.lobby, "hub").toCompletableFuture();
    var queue = transfers.transfer(player(queueId, CompletableFuture::new), this.lobby, "queue").toCompletableFuture();
    assertFalse(transfers.cancel(queueId, "hub"));
    transfers.cancelReason("hub");
    assertEquals(TransferCoordinator.Status.CANCELLED, hub.join().status());
    assertFalse(queue.isDone());
    transfers.close();
    transfers.close();
    assertEquals(TransferCoordinator.Status.CANCELLED, queue.join().status());
    var rejected = transfers.transfer(player(UUID.randomUUID(), () -> { throw new AssertionError("closed"); }),
        this.lobby, "hub").toCompletableFuture().join();
    assertEquals("shutdown", rejected.reason());
    assertEquals(2, transfers.snapshot().cancelled());
    assertEquals(0, transfers.activeCount());
  }

  @Test
  void snapshotsUseCompletedOutcomesAndOneConsistentLatencyWindow() {
    try (var transfers = coordinator()) {
      assertEquals(-1, transfers.snapshot().latencyP95Ms());
      assertEquals(-1, transfers.snapshot().successRatePercent());
      for (int i = 0; i < 3; i++) {
        boolean success = i != 2;
        var connection = CompletableFuture.completedFuture(outcome(success));
        transfers.transfer(player(UUID.randomUUID(), () -> connection), this.lobby, "hub");
      }
      var snapshot = transfers.snapshot();
      assertEquals(2, snapshot.successful());
      assertEquals(1, snapshot.rejected());
      assertEquals(67, snapshot.successRatePercent());
      assertTrue(snapshot.latencyP50Ms() >= 0);
      assertTrue(snapshot.latencyP50Ms() <= snapshot.latencyP95Ms());
      assertTrue(snapshot.latencyP95Ms() <= snapshot.latencyP99Ms());
    }
  }

  @Test
  void callersCannotCompleteInternalRequestOrFreeItsSlot() {
    try (var transfers = coordinator()) {
      UUID id = UUID.randomUUID();
      var request = transfers.transfer(player(id, CompletableFuture::new), this.lobby, "hub");
      request.toCompletableFuture().complete(TransferCoordinator.Result.success("spoof"));
      assertTrue(transfers.isActive(id));
      assertFalse(request.toCompletableFuture().isDone());
      assertEquals(0, transfers.snapshot().successful());
    }
  }

  private static TransferCoordinator coordinator() { return new TransferCoordinator(Duration.ofSeconds(30)); }

  private static RegisteredServer server(String name) {
    var info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
    return (RegisteredServer) Proxy.newProxyInstance(TransferCoordinatorTest.class.getClassLoader(),
        new Class<?>[] {RegisteredServer.class}, (proxy, method, args) -> {
          if (method.getName().equals("getServerInfo")) return info;
          throw new UnsupportedOperationException(method.getName());
        });
  }

  private static Player player(UUID id, Supplier<CompletableFuture<ConnectionRequestBuilder.Result>> connect) {
    var builder = (ConnectionRequestBuilder) Proxy.newProxyInstance(TransferCoordinatorTest.class.getClassLoader(),
        new Class<?>[] {ConnectionRequestBuilder.class}, (proxy, method, args) -> {
          if (method.getName().equals("connect")) return connect.get();
          throw new UnsupportedOperationException(method.getName());
        });
    return (Player) Proxy.newProxyInstance(TransferCoordinatorTest.class.getClassLoader(),
        new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> id;
          case "isActive" -> true;
          case "createConnectionRequest" -> builder;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  private ConnectionRequestBuilder.Result outcome(boolean success) {
    return new ConnectionRequestBuilder.Result() {
      @Override public ConnectionRequestBuilder.Status getStatus() {
        return success ? ConnectionRequestBuilder.Status.SUCCESS : ConnectionRequestBuilder.Status.SERVER_DISCONNECTED;
      }
      @Override public Optional<Component> getReasonComponent() { return Optional.empty(); }
      @Override public RegisteredServer getAttemptedConnection() { return lobby; }
    };
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("connection was not resumed");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }
}
