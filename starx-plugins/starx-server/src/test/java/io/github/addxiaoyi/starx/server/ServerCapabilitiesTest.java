package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class ServerCapabilitiesTest {

  @Test
  void paperAdvertisesMainThreadExecution() {
    Set<String> capabilities = ServerCapabilities.forPlatform(ServerPlatform.PAPER);

    assertTrue(capabilities.contains("bridge.v1"));
    assertTrue(capabilities.contains("bridge.http-exchange"));
    assertTrue(capabilities.contains("server.status"));
    assertTrue(capabilities.contains("scheduler.main"));
    assertFalse(capabilities.contains("scheduler.region"));
  }

  @Test
  void foliaAdvertisesRegionizedExecution() {
    Set<String> capabilities = ServerCapabilities.forPlatform(ServerPlatform.FOLIA);

    assertTrue(capabilities.contains("bridge.v1"));
    assertTrue(capabilities.contains("bridge.http-exchange"));
    assertTrue(capabilities.contains("server.status"));
    assertTrue(capabilities.contains("scheduler.global"));
    assertTrue(capabilities.contains("scheduler.region"));
    assertFalse(capabilities.contains("scheduler.main"));
  }
}
