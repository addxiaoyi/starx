package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

final class BackendHeartbeatClientTest {

  @Test
  void buildsAuthenticatedHeartbeatRequestWithoutPuttingTheKeyInTheUri() {
    BridgeMessage status = BridgeMessage.statusResponse(
        "factions",
        PlatformKind.PAPER,
        "heartbeat-1",
        Map.of("online", "0", "max", "20"));

    HttpRequest request = BackendHeartbeatClient.buildRequest(
        URI.create("http://127.0.0.1:8788"),
        "server-secret",
        "factions",
        status,
        Duration.ofSeconds(4));

    assertEquals("http://127.0.0.1:8788/v1/backend/heartbeat", request.uri().toString());
    assertEquals("POST", request.method());
    assertEquals("server-secret", request.headers().firstValue("X-API-Key").orElseThrow());
    assertEquals("factions", request.headers().firstValue("X-StarX-Server").orElseThrow());
    assertEquals(-1, request.uri().toString().indexOf("server-secret"));
    assertArrayEquals(BridgeProtocol.encode(status), BackendHeartbeatClient.decodeBody(
        BackendHeartbeatClient.encodeBody(status)));
  }

  @Test
  void heartbeatFailureMessageFallsBackToTheRootCauseTypeWhenMessageIsMissing() {
    CompletionException error = new CompletionException(
        new CompletionException(new java.net.ConnectException()));

    assertEquals("ConnectException", StarxServerPlugin.heartbeatFailureMessage(error));
  }

  @Test
  void decodesAnOptionalProxyCommandFromTheHeartbeatResponse() {
    BridgeMessage command = BridgeMessage.skinRequest(
        "proxy",
        "skin-1",
        "4f06bce0-32d7-4d4d-bb17-9f7e92ae8701",
        "Alex");

    BridgeMessage decoded = BackendHeartbeatClient.decodeCommandResponse(
        200, BackendHeartbeatClient.encodeBody(command)).orElseThrow();

    assertEquals(BridgeProtocol.SKIN_REQUEST, decoded.type());
    assertEquals("skin-1", decoded.correlationId());
    assertTrue(BackendHeartbeatClient.decodeCommandResponse(200, "").isEmpty());
    assertTrue(BackendHeartbeatClient.decodeCommandResponse(204, "").isEmpty());
  }
}
