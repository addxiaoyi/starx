package io.github.addxiaoyi.starx.velocity.module.proxytools.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SmartQueueServiceTest {

  @Test
  void snapshotExposesQueueSizesWithoutPlayers() {
    SmartQueueService queue = new SmartQueueService();
    RegisteredServer server = server("survival");
    queue.enqueue(server, player("00000000-0000-0000-0000-000000000001", true), 100);

    assertEquals(Map.of("survival", 1), queue.snapshot());
  }

  @Test
  void clearReleasesQueuedPlayersAndJoinTimestamps() {
    SmartQueueService service = new SmartQueueService();
    RegisteredServer server = server("survival");
    service.enqueue(server, player("00000000-0000-0000-0000-000000000001", true), 100);
    service.clear();
    assertEquals(Map.of(), service.snapshot());
  }

  @Test
  void priorityQueueIsIdempotentAndReportsRankAndEta() {
    SmartQueueService queue = new SmartQueueService();
    RegisteredServer server = server("survival");
    Player normal = player("00000000-0000-0000-0000-000000000001", true);
    Player vip = player("00000000-0000-0000-0000-000000000002", true);
    queue.enqueue(server, normal, 100);
    queue.enqueue(server, normal, 100);
    queue.enqueue(server, vip, 500);

    assertEquals(2, queue.size(server));
    assertEquals(1, queue.position(server, vip));
    assertEquals(2, queue.position(server, normal));
    assertEquals(3, queue.estimateWaitSeconds(server, normal, 2, 3_000L));
    assertEquals(vip.getUniqueId(), queue.dequeue(server).getUniqueId());
  }

  @Test
  void offlinePriorityHeadDoesNotBlockActivePlayer() {
    SmartQueueService queue = new SmartQueueService();
    RegisteredServer server = server("survival");
    queue.enqueue(server, player("00000000-0000-0000-0000-000000000001", false), 500);
    queue.enqueue(server, player("00000000-0000-0000-0000-000000000002", true), 100);
    AtomicInteger attempts = new AtomicInteger();

    assertEquals(1, queue.processQueues((player, ignored) -> {
      attempts.incrementAndGet();
      return CompletableFuture.completedFuture(true);
    }, 2));
    assertEquals(1, attempts.get());
    assertEquals(0, queue.size(server));
  }

  @Test
  void pendingVipDoesNotSerializeLowerPriorityConnections() {
    SmartQueueService queue = new SmartQueueService();
    RegisteredServer server = server("survival");
    Player vip = player("00000000-0000-0000-0000-000000000001", true);
    Player second = player("00000000-0000-0000-0000-000000000002", true);
    Player third = player("00000000-0000-0000-0000-000000000003", true);
    CompletableFuture<Boolean> slow = new CompletableFuture<>();
    AtomicInteger attempts = new AtomicInteger();
    queue.enqueue(server, vip, 500);
    queue.enqueue(server, second, 300);
    queue.enqueue(server, third, 100);

    assertEquals(3, queue.processQueues((player, ignored) -> {
      attempts.incrementAndGet();
      return player.getUniqueId().equals(vip.getUniqueId())
          ? slow
          : CompletableFuture.completedFuture(true);
    }, 3));
    assertEquals(3, attempts.get());
    assertEquals(1, queue.size(server));
    assertEquals(0, queue.processQueues((player, ignored) ->
        CompletableFuture.completedFuture(true), 3));

    slow.complete(false);
    assertEquals(1, queue.size(server));
    assertEquals(1, queue.processQueues((player, ignored) ->
        CompletableFuture.completedFuture(true), 3));
    assertEquals(0, queue.size(server));
  }

  @Test
  void releaseLimitCapsConcurrentAttempts() {
    SmartQueueService queue = new SmartQueueService();
    RegisteredServer server = server("survival");
    Player first = player("00000000-0000-0000-0000-000000000001", true);
    Player second = player("00000000-0000-0000-0000-000000000002", true);
    Player third = player("00000000-0000-0000-0000-000000000003", true);
    CompletableFuture<Boolean> firstAttempt = new CompletableFuture<>();
    CompletableFuture<Boolean> secondAttempt = new CompletableFuture<>();
    AtomicInteger attempts = new AtomicInteger();
    queue.enqueue(server, first, 500);
    queue.enqueue(server, second, 300);
    queue.enqueue(server, third, 100);

    assertEquals(2, queue.processQueues((player, ignored) -> {
      attempts.incrementAndGet();
      return player.getUniqueId().equals(first.getUniqueId())
          ? firstAttempt
          : secondAttempt;
    }, 2));
    assertEquals(2, attempts.get());
    assertEquals(0, queue.processQueues((player, ignored) -> {
      attempts.incrementAndGet();
      return CompletableFuture.completedFuture(true);
    }, 2));

    firstAttempt.complete(true);
    assertEquals(2, queue.size(server));
    assertEquals(1, queue.processQueues((player, ignored) ->
        CompletableFuture.completedFuture(true), 2));
    assertEquals(1, queue.size(server));
    secondAttempt.complete(false);
    assertEquals(1, queue.processQueues((player, ignored) ->
        CompletableFuture.completedFuture(true), 2));
    assertEquals(0, queue.size(server));
  }

  @Test
  void synchronousConnectorFailureDoesNotLeakClaim() {
    SmartQueueService queue = new SmartQueueService();
    RegisteredServer server = server("survival");
    queue.enqueue(server, player("00000000-0000-0000-0000-000000000001", true), 100);

    assertEquals(0, queue.processQueues((player, ignored) -> {
      throw new IllegalStateException("executor closed");
    }, 1));
    assertEquals(1, queue.size(server));
    assertEquals(1, queue.processQueues((player, ignored) ->
        CompletableFuture.completedFuture(true), 1));
    assertEquals(0, queue.size(server));
  }

  @Test
  void staleConnectionCannotRemoveReplacementQueueEntry() {
    SmartQueueService queue = new SmartQueueService();
    RegisteredServer server = server("survival");
    Player oldConnection = player("00000000-0000-0000-0000-000000000001", true);
    Player replacement = player("00000000-0000-0000-0000-000000000001", true);

    queue.enqueue(server, replacement, 100);
    queue.recordQuit(oldConnection);

    assertEquals(1, queue.size(server));
  }

  @Test
  void staleAsyncResultCannotCompleteReplacementQueueEntry() {
    SmartQueueService queue = new SmartQueueService();
    RegisteredServer server = server("survival");
    Player oldConnection = player("00000000-0000-0000-0000-000000000001", true);
    Player replacement = player("00000000-0000-0000-0000-000000000001", true);
    CompletableFuture<Boolean> oldResult = new CompletableFuture<>();

    queue.enqueue(server, oldConnection, 100);
    assertEquals(1, queue.processQueues((ignored, serverName) -> oldResult, 1));
    queue.enqueue(server, replacement, 100);
    oldResult.complete(true);

    assertEquals(1, queue.size(server));
  }

  private static RegisteredServer server(String name) {
    ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
    return (RegisteredServer) Proxy.newProxyInstance(
        SmartQueueServiceTest.class.getClassLoader(), new Class<?>[] {RegisteredServer.class},
        (proxy, method, args) -> method.getName().equals("getServerInfo") ? info : null);
  }

  private static Player player(String id, boolean active) {
    UUID uuid = UUID.fromString(id);
    return (Player) Proxy.newProxyInstance(
        SmartQueueServiceTest.class.getClassLoader(), new Class<?>[] {Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> uuid;
          case "isActive" -> active;
          case "hashCode" -> uuid.hashCode();
          case "equals" -> proxy == args[0];
          default -> null;
        });
  }
}
