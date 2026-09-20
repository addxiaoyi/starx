package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class StarxPlaceholderExpansionTest {

  @Test
  void exposesBackendAndBridgeStateToPlaceholderApi() {
    BackendBridgeSession session = new BackendBridgeSession(
        "factions",
        ServerPlatform.PAPER,
        () -> Map.of(
            "online", "7",
            "max", "100",
            "memoryUsedMb", "1536",
            "memoryMaxMb", "4096",
            "memoryPercent", "37"),
        Clock.systemUTC());
    StarxPlaceholderExpansion expansion = new StarxPlaceholderExpansion(session, "0.1.4");

    assertEquals("starx", expansion.getIdentifier());
    assertTrue(expansion.persist());
    assertEquals("factions", expansion.onRequest(null, "node"));
    assertEquals("paper", expansion.onRequest(null, "platform"));
    assertEquals("main-thread", expansion.onRequest(null, "execution"));
    assertEquals("7", expansion.onRequest(null, "online"));
    assertEquals("100", expansion.onRequest(null, "max"));
    assertEquals("1536", expansion.onRequest(null, "memory_used_mb"));
    assertEquals("4096", expansion.onRequest(null, "memory_max_mb"));
    assertEquals("37", expansion.onRequest(null, "memory_percent"));
    assertEquals("未连接", expansion.onRequest(null, "proxy_status"));
    assertNull(expansion.onRequest(null, "unknown"));
  }
}
