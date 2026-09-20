package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.velocity.bridge.BackendCommandMailbox;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BackendHeartbeatHandlerTest {

  @Test
  void decodesStatusOnlyForAKnownVelocityServer() {
    BridgeMessage status = BridgeMessage.statusResponse(
        "factions",
        PlatformKind.PAPER,
        "heartbeat-1",
        Map.of("online", "0", "max", "20", "skinProvider", "none"));
    String body = Base64.getEncoder().encodeToString(BridgeProtocol.encode(status));

    BridgeMessage decoded = BackendHeartbeatHandler.decode(
        "factions", body, "factions"::equals);

    assertEquals(BridgeProtocol.STATUS_RESPONSE, decoded.type());
    assertEquals("0", decoded.attributes().get("online"));
    assertEquals("heartbeat-http", decoded.attributes().get("transport"));
    assertThrows(IllegalArgumentException.class, () -> BackendHeartbeatHandler.decode(
        "unknown", body, "factions"::equals));
  }

  @Test
  void rejectsNonStatusAndMalformedHeartbeatBodies() {
    BridgeMessage hello = BridgeMessage.hello("factions", PlatformKind.PAPER);
    String helloBody = Base64.getEncoder().encodeToString(BridgeProtocol.encode(hello));

    assertThrows(IllegalArgumentException.class, () -> BackendHeartbeatHandler.decode(
        "factions", helloBody, "factions"::equals));
    assertThrows(IllegalArgumentException.class, () -> BackendHeartbeatHandler.decodeExchange(
        "factions", helloBody, "factions"::equals));
    assertThrows(IllegalArgumentException.class, () -> BackendHeartbeatHandler.decode(
        "factions", "not-base64!", "factions"::equals));
    assertThrows(IllegalArgumentException.class, () -> BackendHeartbeatHandler.decode(
        "../factions", helloBody, name -> true));
  }

  @Test
  void acceptsSkinResponsesOnTheAuthenticatedBackendExchange() {
    BridgeMessage response = BridgeMessage.skinResponse(
        "factions",
        PlatformKind.PAPER,
        "skin-1",
        Map.of(
            "uuid", "4f06bce0-32d7-4d4d-bb17-9f7e92ae8701",
            "name", "Alex",
            "found", "false"));
    String body = Base64.getEncoder().encodeToString(BridgeProtocol.encode(response));

    BridgeMessage decoded = BackendHeartbeatHandler.decodeExchange(
        "factions", body, "factions"::equals);

    assertEquals(BridgeProtocol.SKIN_RESPONSE, decoded.type());
    assertEquals("skin-1", decoded.correlationId());
  }

  @Test
  void exchangesStatusForQueuedCommandsAndRoutesSkinResponses() {
    BackendNodeRegistry registry = new BackendNodeRegistry();
    BackendCommandMailbox mailbox = new BackendCommandMailbox(4);
    AtomicReference<BridgeMessage> receivedSkin = new AtomicReference<>();
    Clock clock = Clock.fixed(
        Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC);
    BridgeMessage command = BridgeMessage.skinRequest(
        "proxy",
        "skin-1",
        "4f06bce0-32d7-4d4d-bb17-9f7e92ae8701",
        "Alex");
    mailbox.offer("factions", command);
    BridgeMessage status = BridgeMessage.statusResponse(
        "factions",
        PlatformKind.PAPER,
        "heartbeat-1",
        Map.of("online", "0", "max", "20"));

    BridgeMessage delivered = BackendHeartbeatHandler.exchange(
        "factions",
        Base64.getEncoder().encodeToString(BridgeProtocol.encode(status)),
        "factions"::equals,
        registry,
        mailbox,
        receivedSkin::set,
        clock).orElseThrow();

    assertEquals("skin-1", delivered.correlationId());
    assertEquals(
        "heartbeat-http",
        registry.find("factions").orElseThrow().status().get("transport"));
    Map<String, String> transport = registry.find("factions").orElseThrow().status();
    assertEquals("1", transport.get("httpCommandsAccepted"));
    assertEquals("1", transport.get("httpCommandsDelivered"));
    assertEquals("0", transport.get("httpCommandsRejected"));
    assertEquals("0", transport.get("httpCommandsQueued"));

    BridgeMessage skin = BridgeMessage.skinResponse(
        "factions",
        PlatformKind.PAPER,
        "skin-1",
        Map.of(
            "uuid", "4f06bce0-32d7-4d4d-bb17-9f7e92ae8701",
            "name", "Alex",
            "found", "false"));
    assertTrue(BackendHeartbeatHandler.exchange(
        "factions",
        Base64.getEncoder().encodeToString(BridgeProtocol.encode(skin)),
        "factions"::equals,
        registry,
        mailbox,
        receivedSkin::set,
        clock).isEmpty());
    assertEquals("skin-1", receivedSkin.get().correlationId());
  }
}
