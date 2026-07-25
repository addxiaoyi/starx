package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class BackendHeartbeatConfigTest {

  @Test
  void acceptsASecureBoundedHeartbeatConfiguration() {
    BackendHeartbeatConfig config = BackendHeartbeatConfig.create(
        true, "http://127.0.0.1:8788", "shared-key", "factions", 15, 4_000);

    assertTrue(config.enabled());
    assertEquals(URI.create("http://127.0.0.1:8788"), config.velocityUrl());
    assertEquals("shared-key", config.apiKey());
    assertEquals("factions", config.serverName());
    assertEquals(Duration.ofSeconds(15), config.interval());
    assertEquals(Duration.ofSeconds(4), config.timeout());
  }

  @Test
  void disabledHeartbeatDoesNotRequireASecret() {
    BackendHeartbeatConfig config = BackendHeartbeatConfig.create(
        false, "http://127.0.0.1:8788", "", "factions", 15, 4_000);

    assertFalse(config.enabled());
  }

  @Test
  void rejectsUnsafeOrSpammyEnabledConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> BackendHeartbeatConfig.create(
        true, "file:///tmp/status", "shared-key", "factions", 15, 4_000));
    assertThrows(IllegalArgumentException.class, () -> BackendHeartbeatConfig.create(
        true, "http://127.0.0.1:8788", "", "factions", 15, 4_000));
    assertThrows(IllegalArgumentException.class, () -> BackendHeartbeatConfig.create(
        true, "http://127.0.0.1:8788", "shared-key", "factions", 1, 4_000));
  }
}
