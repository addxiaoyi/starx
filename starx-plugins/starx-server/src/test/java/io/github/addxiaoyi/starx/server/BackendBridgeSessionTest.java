package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BackendBridgeSessionTest {

  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

  @Test
  void proxyHelloProducesBackendHelloAndTracksContact() {
    BackendBridgeSession session = session();

    BridgeMessage response = session.receive(
        BridgeMessage.hello("proxy", PlatformKind.VELOCITY)).orElseThrow();

    assertEquals(BridgeProtocol.BACKEND_HELLO, response.type());
    assertEquals("lobby", response.nodeId());
    assertEquals(PlatformKind.PAPER, response.platform());
    assertEquals(NOW, session.lastProxyContact().orElseThrow());
  }

  @Test
  void statusRequestProducesCorrelatedResponse() {
    BackendBridgeSession session = session();
    BridgeMessage request = BridgeMessage.statusRequest("proxy", "request-7");

    BridgeMessage response = session.receive(request).orElseThrow();

    assertEquals(BridgeProtocol.STATUS_RESPONSE, response.type());
    assertEquals("request-7", response.correlationId());
    assertEquals("3", response.attributes().get("online"));
    assertEquals("100", response.attributes().get("max"));
    assertTrue(response.attributes().get("capabilities").contains("scheduler.main"));
  }

  @Test
  void heartbeatStatusDoesNotPretendTheProxyContactedTheBackend() {
    BackendBridgeSession session = session();

    BridgeMessage response = session.statusReport("heartbeat-7");

    assertEquals(BridgeProtocol.STATUS_RESPONSE, response.type());
    assertEquals("heartbeat-7", response.correlationId());
    assertEquals("3", response.attributes().get("online"));
    assertTrue(session.lastProxyContact().isEmpty());
  }

  @Test
  void ignoresMessagesThatDoNotComeFromVelocity() {
    BackendBridgeSession session = session();
    BridgeMessage backendMessage = BridgeMessage.hello("other", PlatformKind.PAPER);

    assertTrue(session.receive(backendMessage).isEmpty());
    assertTrue(session.lastProxyContact().isEmpty());
  }

  @Test
  void skinRequestReturnsBackendTextureData() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    BackendBridgeSession session = new BackendBridgeSession(
        "lobby",
        ServerPlatform.PAPER,
        () -> Map.of("online", "3", "max", "100"),
        (requestedUuid, name) -> Optional.of(new BackendSkinProfile(
            requestedUuid, name, "skinsrestorer", "texture-value", "texture-signature")),
        Clock.fixed(NOW, ZoneOffset.UTC));

    BridgeMessage response = session.receive(BridgeMessage.skinRequest(
        "proxy", "skin-7", uuid.toString(), "Alex")).orElseThrow();

    assertEquals(BridgeProtocol.SKIN_RESPONSE, response.type());
    assertEquals("skin-7", response.correlationId());
    assertEquals("skinsrestorer", response.attributes().get("provider"));
    assertEquals("texture-value", response.attributes().get("value"));
    assertEquals("texture-signature", response.attributes().get("signature"));
  }

  @Test
  void persistsWebsiteSkinUpdateWithoutAnOnlinePlayer() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    AtomicReference<String> stored = new AtomicReference<>();
    BackendSkinResolver resolver = new BackendSkinResolver() {
      @Override
      public Optional<BackendSkinProfile> find(UUID requestedUuid, String name) {
        return Optional.empty();
      }

      @Override
      public boolean store(
          UUID requestedUuid,
          String name,
          String value,
          String signature
      ) {
        stored.set(requestedUuid + ":" + name + ":" + value + ":" + signature);
        return true;
      }
    };
    BackendBridgeSession session = new BackendBridgeSession(
        "lobby",
        ServerPlatform.PAPER,
        () -> Map.of("online", "0", "max", "100"),
        resolver,
        Clock.fixed(NOW, ZoneOffset.UTC));

    Optional<BridgeMessage> response = session.receive(BridgeMessage.skinUpdate(
        "proxy", "skin-update-7", uuid.toString(), "Alex", "texture-value", ""));

    assertTrue(response.isEmpty());
    assertEquals(uuid + ":Alex:texture-value:", stored.get());
    assertEquals(NOW, session.lastProxyContact().orElseThrow());
  }

  @Test
  void appliesMaintenanceConfigWithoutRequiringAPlayerCarrier() {
    AtomicReference<Boolean> maintenance = new AtomicReference<>();
    BackendBridgeSession session = new BackendBridgeSession(
        "lobby",
        ServerPlatform.PAPER,
        () -> Map.of("online", "0", "max", "100"),
        (uuid, name) -> Optional.empty(),
        maintenance::set,
        Clock.fixed(NOW, ZoneOffset.UTC));

    Optional<BridgeMessage> response = session.receive(
        BridgeMessage.maintenanceConfig("proxy", "maint-1", true));

    assertTrue(response.isEmpty());
    assertEquals(true, maintenance.get());
    assertEquals(NOW, session.lastProxyContact().orElseThrow());
  }

  private static BackendBridgeSession session() {
    return new BackendBridgeSession(
        "lobby",
        ServerPlatform.PAPER,
        () -> Map.of("online", "3", "max", "100"),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }
}
