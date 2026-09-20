package io.github.addxiaoyi.starx.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PendingSkinRequestsTest {

  private static final UUID PLAYER_ID =
      UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");

  @Test
  void rejectsAResponseForAnOlderRequest() {
    PendingSkinRequests requests = new PendingSkinRequests();
    Instant now = Instant.parse("2026-08-17T00:00:00Z");

    requests.register(PLAYER_ID, "new", now);
    requests.register(PLAYER_ID, "latest", now);

    assertFalse(requests.accept(response("new"), now.plusSeconds(1)));
    assertTrue(requests.accept(response("latest"), now.plusSeconds(1)));
  }

  @Test
  void rejectsResponsesAfterTheRequestExpires() {
    PendingSkinRequests requests = new PendingSkinRequests();
    Instant now = Instant.parse("2026-08-17T00:00:00Z");

    requests.register(PLAYER_ID, "request", now);

    assertFalse(requests.accept(response("request"), now.plus(PendingSkinRequests.TTL)));
  }

  private static BridgeMessage response(String correlationId) {
    return new BridgeMessage(
        BridgeProtocol.SKIN_RESPONSE,
        "backend",
        PlatformKind.PAPER,
        correlationId,
        Map.of("uuid", PLAYER_ID.toString(), "found", "false"));
  }
}
