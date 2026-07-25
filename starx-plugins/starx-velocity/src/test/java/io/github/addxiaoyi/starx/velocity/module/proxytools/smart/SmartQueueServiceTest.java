package io.github.addxiaoyi.starx.velocity.module.proxytools.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
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
      return true;
    }, 2));
    assertEquals(1, attempts.get());
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
