package io.github.addxiaoyi.starx.velocity.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.common.platform.ServerRoutingEngine;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import io.github.addxiaoyi.starx.velocity.module.proxytools.smart.SmartQueueService;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class SmartQueueFailoverStressTest {
  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
  private static final int ACTIVE_PLAYERS = 12_000;
  private static final int INACTIVE_PLAYERS = 2_000;
  private static final int NODE_CAPACITY = 4_000;

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void drainsConcurrentQueueAcrossHealthyShardsWhenPreferredNodesFail() throws Exception {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    Map<String, Integer> online = new ConcurrentHashMap<>();
    for (int index = 1; index <= 6; index++) {
      String name = "survival-" + index;
      online.put(name, 0);
      update(registry, name, 0, index == 2 ? NOW.minusSeconds(90) : NOW);
    }
    registry.markHeartbeatMissed("survival-1");
    registry.markHeartbeatMissed("survival-1");

    BackendRoutingService routing = new BackendRoutingService(
        registry,
        new ServerRoutingEngine(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofSeconds(45));
    SmartQueueService queue = new SmartQueueService();
    RegisteredServer preferred = server("survival-1");
    List<Player> players = new ArrayList<>(ACTIVE_PLAYERS + INACTIVE_PLAYERS);
    for (int index = 0; index < ACTIVE_PLAYERS + INACTIVE_PLAYERS; index++) {
      players.add(player(index, index < ACTIVE_PLAYERS));
    }

    try (var executor = Executors.newFixedThreadPool(8)) {
      for (Player player : players) {
        executor.submit(() -> {
          queue.enqueue(preferred, player, 100);
          queue.enqueue(preferred, player, 100);
        });
      }
      executor.shutdown();
      assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
    }
    assertEquals(ACTIVE_PLAYERS + INACTIVE_PLAYERS, queue.size(preferred));

    Map<String, Integer> admitted = new HashMap<>();
    int connected = 0;
    int cycles = 0;
    while (queue.size(preferred) > 0 && cycles++ < 200) {
      connected += queue.processQueues((player, originalServer) -> {
        String selected = routing.select(originalServer, queue.snapshot())
            .orElseThrow(() -> new AssertionError("healthy failover shard was not selected"))
            .nodeId();
        assertFalse(Set.of("survival-1", "survival-2").contains(selected),
            () -> "draining or stale node received an admission: selected=" + selected
                + " online=" + online + " admitted=" + admitted);
        int next = online.compute(selected, (ignored, value) -> value == null ? 1 : value + 1);
        admitted.merge(selected, 1, Integer::sum);
        update(registry, selected, next, NOW);
        return CompletableFuture.completedFuture(true);
      }, 500);
    }

    assertEquals(0, queue.size(preferred));
    assertEquals(ACTIVE_PLAYERS, connected);
    assertEquals(ACTIVE_PLAYERS, admitted.values().stream().mapToInt(Integer::intValue).sum());
    assertTrue(admitted.size() >= 2, "load was not distributed across healthy shards");
    assertTrue(admitted.keySet().stream().noneMatch(Set.of("survival-1", "survival-2")::contains));
    assertTrue(admitted.values().stream().allMatch(count -> count <= NODE_CAPACITY));
  }

  private static void update(
      BackendNodeRegistry registry, String name, int playerCount, Instant seenAt) {
    registry.update(name, BridgeMessage.statusResponse(
        name,
        PlatformKind.PAPER,
        "stress-" + name + '-' + playerCount,
        Map.of(
            "online", Integer.toString(playerCount),
            "max", Integer.toString(NODE_CAPACITY),
            "serverType", "survival",
            "mspt", "20.0",
            "latencyMs", "10",
            "admissionsPerMinute", "600")), seenAt);
  }

  private static RegisteredServer server(String name) {
    ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
    return (RegisteredServer) Proxy.newProxyInstance(
        SmartQueueFailoverStressTest.class.getClassLoader(),
        new Class<?>[] {RegisteredServer.class},
        (proxy, method, args) -> method.getName().equals("getServerInfo") ? info : null);
  }

  private static Player player(int index, boolean active) {
    UUID uuid = new UUID(0x51A7L, index + 1L);
    return (Player) Proxy.newProxyInstance(
        SmartQueueFailoverStressTest.class.getClassLoader(),
        new Class<?>[] {Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> uuid;
          case "isActive" -> active;
          case "hashCode" -> uuid.hashCode();
          case "equals" -> proxy == args[0];
          default -> null;
        });
  }
}
