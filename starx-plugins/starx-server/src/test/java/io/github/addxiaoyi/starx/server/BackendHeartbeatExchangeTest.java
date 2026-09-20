package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BackendHeartbeatExchangeTest {

  @Test
  void processesAQueuedSkinRequestWithoutAPlayerCarrier() throws Exception {
    AtomicInteger exchanges = new AtomicInteger();
    AtomicInteger scheduledCommands = new AtomicInteger();
    AtomicReference<BridgeMessage> skinResponse = new AtomicReference<>();
    BridgeMessage skinRequest = BridgeMessage.skinRequest(
        "proxy",
        "skin-http-1",
        "4f06bce0-32d7-4d4d-bb17-9f7e92ae8701",
        "Alex");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/backend/heartbeat", exchange -> {
      int sequence = exchanges.incrementAndGet();
      String body = new String(
          exchange.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII);
      BridgeMessage incoming = BridgeProtocol.decode(BackendHeartbeatClient.decodeBody(body));
      byte[] response;
      if (sequence == 1) {
        assertEquals(BridgeProtocol.STATUS_RESPONSE, incoming.type());
        response = BackendHeartbeatClient.encodeBody(skinRequest)
            .getBytes(StandardCharsets.US_ASCII);
      } else {
        assertEquals(BridgeProtocol.SKIN_RESPONSE, incoming.type());
        skinResponse.set(incoming);
        response = new byte[0];
      }
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();

    try {
      URI velocity = URI.create(
          "http://127.0.0.1:" + server.getAddress().getPort());
      BackendHeartbeatClient client = new BackendHeartbeatClient(
          velocity, "server-secret", "factions", Duration.ofSeconds(4));
      BackendBridgeSession session = new BackendBridgeSession(
          "factions",
          ServerPlatform.PAPER,
          () -> Map.of("online", "0", "max", "20"),
          (uuid, name) -> Optional.of(new BackendSkinProfile(
              uuid, name, "skinsrestorer", "texture-value", "texture-signature")),
          Clock.systemUTC());

      BackendHeartbeatExchange.run(
          client,
          session,
          session.statusReport("heartbeat-1"),
          4,
          command -> {
            scheduledCommands.incrementAndGet();
            return CompletableFuture.completedFuture(command.get());
          }).join();

      assertEquals(2, exchanges.get());
      assertEquals(1, scheduledCommands.get());
      assertEquals(BridgeProtocol.SKIN_RESPONSE, skinResponse.get().type());
      assertEquals("skin-http-1", skinResponse.get().correlationId());
      assertEquals("skinsrestorer", skinResponse.get().attributes().get("provider"));
      assertTrue(Boolean.parseBoolean(skinResponse.get().attributes().get("found")));
    } finally {
      server.stop(0);
    }
  }
}
