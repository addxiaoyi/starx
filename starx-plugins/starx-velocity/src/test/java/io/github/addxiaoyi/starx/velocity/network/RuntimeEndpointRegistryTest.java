package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeEndpointRegistryTest {
  @TempDir
  Path temporary;

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-07-29T10:00:00Z"),
      ZoneOffset.UTC);

  @Test
  void publishesLiveEndpointAndPreservesLeaseAfterClose() throws Exception {
    Path endpoint;
    Path lease;
    try (RuntimeEndpointRegistry registry =
        RuntimeEndpointRegistry.open(this.temporary, CLOCK)) {
      registry.publish(
          new StarxConfig.HttpConfig("0.0.0.0", 8788),
          new StarxConfig.HttpConfig("0.0.0.0", 8791),
          new TcpPortAllocator.Selection(
              8788, 8791, List.of(8788), List.of(), false));
      endpoint = registry.endpointFile();
      lease = registry.leaseFile();

      JsonObject published = JsonParser.parseString(
          Files.readString(endpoint)).getAsJsonObject();
      assertEquals(8791, published.get("effectivePort").getAsInt());
      assertEquals("http://127.0.0.1:8791", published.get("localBaseUrl").getAsString());
      assertTrue(Files.isRegularFile(registry.lockFile()));
      assertThrows(
          IOException.class,
          () -> RuntimeEndpointRegistry.open(this.temporary, CLOCK));
    }

    assertFalse(Files.exists(endpoint));
    assertTrue(Files.isRegularFile(lease));

    try (RuntimeEndpointRegistry reopened =
        RuntimeEndpointRegistry.open(this.temporary, CLOCK)) {
      assertEquals(8791, reopened.leasedPort(8788).orElseThrow());
    }
  }

  @Test
  void ignoresLeaseAfterConfiguredPortChanges() throws Exception {
    try (RuntimeEndpointRegistry registry =
        RuntimeEndpointRegistry.open(this.temporary, CLOCK)) {
      registry.publish(
          new StarxConfig.HttpConfig("127.0.0.1", 8788),
          new StarxConfig.HttpConfig("127.0.0.1", 8791),
          new TcpPortAllocator.Selection(
              8788, 8791, List.of(8788), List.of(), false));
    }

    try (RuntimeEndpointRegistry reopened =
        RuntimeEndpointRegistry.open(this.temporary, CLOCK)) {
      assertTrue(reopened.leasedPort(8788).isPresent());
      assertTrue(reopened.leasedPort(9000).isEmpty());
    }
  }

  @Test
  void ignoresMalformedLease() throws Exception {
    Files.writeString(
        this.temporary.resolve(RuntimeEndpointRegistry.LEASE_FILE_NAME),
        "{not-json");

    try (RuntimeEndpointRegistry registry =
        RuntimeEndpointRegistry.open(this.temporary, CLOCK)) {
      assertTrue(registry.leasedPort(8788).isEmpty());
    }
  }
}
