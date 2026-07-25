package io.github.addxiaoyi.starx.velocity.module.proxytools.queue;

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

class QueueServiceTest {

  @Test
  void snapshotExposesQueueSizesWithoutPlayers() {
    QueueService service = new QueueService();
    RegisteredServer server = server("survival");
    service.enqueue(server, player("00000000-0000-0000-0000-000000000001", true));

    assertEquals(Map.of("survival", 1), service.snapshot());
  }

  @Test
  void clearReleasesAllQueuedPlayers() {
    QueueService service = new QueueService();
    RegisteredServer server = server("survival");
    service.enqueue(server, player("00000000-0000-0000-0000-000000000001", true));
    service.clear();
    assertEquals(Map.of(), service.snapshot());
  }
  @Test
  void duplicateKickKeepsOneEntryAndReportsPositionAndEta() {
    QueueService queue = new QueueService();
    RegisteredServer server = server("survival");
    Player first = player("00000000-0000-0000-0000-000000000001", true);
    Player second = player("00000000-0000-0000-0000-000000000002", true);

    queue.enqueue(server, first);
    queue.enqueue(server, first);
    queue.enqueue(server, second);

    assertEquals(2, queue.size(server));
    assertEquals(1, queue.position(server, first));
    assertEquals(2, queue.position(server, second));
    assertEquals(6, queue.estimateWaitSeconds(server, second, 1, 3_000L));
  }

  @Test
  void offlineHeadIsRemovedWithoutBlockingNextPlayer() {
    QueueService queue = new QueueService();
    RegisteredServer server = server("survival");
    queue.enqueue(server, player("00000000-0000-0000-0000-000000000001", false));
    queue.enqueue(server, player("00000000-0000-0000-0000-000000000002", true));
    AtomicInteger attempts = new AtomicInteger();

    assertEquals(1, queue.processQueues((player, ignored) -> {
      attempts.incrementAndGet();
      return true;
    }));
    assertEquals(1, attempts.get());
    assertEquals(0, queue.size(server));
  }

  private static RegisteredServer server(String name) {
    ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
    return (RegisteredServer) Proxy.newProxyInstance(
        QueueServiceTest.class.getClassLoader(), new Class<?>[] {RegisteredServer.class},
        (proxy, method, args) -> method.getName().equals("getServerInfo") ? info : null);
  }

  private static Player player(String id, boolean active) {
    UUID uuid = UUID.fromString(id);
    return (Player) Proxy.newProxyInstance(
        QueueServiceTest.class.getClassLoader(), new Class<?>[] {Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> uuid;
          case "isActive" -> active;
          case "hashCode" -> uuid.hashCode();
          case "equals" -> proxy == args[0];
          default -> null;
        });
  }
}
