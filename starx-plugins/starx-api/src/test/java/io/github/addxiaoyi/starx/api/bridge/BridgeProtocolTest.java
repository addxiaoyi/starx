package io.github.addxiaoyi.starx.api.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BridgeProtocolTest {

  @Test
  void roundTripsStatusWithoutChangingFields() {
    BridgeMessage source = new BridgeMessage(
        BridgeProtocol.STATUS_RESPONSE,
        "lobby",
        PlatformKind.PAPER,
        "request-1",
        Map.of("online", "4", "max", "100"));

    assertEquals(source, BridgeProtocol.decode(BridgeProtocol.encode(source)));
  }

  @Test
  void roundTripsCorrelatedSkinRequest() {
    BridgeMessage source = BridgeMessage.skinRequest(
        "proxy", "skin-1", "4f06bce0-32d7-4d4d-bb17-9f7e92ae8701", "Alex");

    BridgeMessage decoded = BridgeProtocol.decode(BridgeProtocol.encode(source));

    assertEquals(source, decoded);
    assertEquals(BridgeProtocol.SKIN_REQUEST, decoded.type());
  }

  @Test
  void producesDeterministicBytesForAttributeOrder() {
    Map<String, String> forward = new LinkedHashMap<>();
    forward.put("online", "4");
    forward.put("max", "100");
    Map<String, String> reverse = new LinkedHashMap<>();
    reverse.put("max", "100");
    reverse.put("online", "4");

    BridgeMessage first = new BridgeMessage(
        BridgeProtocol.STATUS_RESPONSE, "lobby", PlatformKind.PAPER, "request-1", forward);
    BridgeMessage second = new BridgeMessage(
        BridgeProtocol.STATUS_RESPONSE, "lobby", PlatformKind.PAPER, "request-1", reverse);

    assertEquals(
        java.util.HexFormat.of().formatHex(BridgeProtocol.encode(first)),
        java.util.HexFormat.of().formatHex(BridgeProtocol.encode(second)));
  }

  @Test
  void rejectsUnsupportedProtocolVersion() {
    byte[] payload = BridgeProtocol.encode(new BridgeMessage(
        BridgeProtocol.BACKEND_HELLO, "lobby", PlatformKind.PAPER, "", Map.of()));
    payload[5] = 2;

    assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(payload));
  }

  @Test
  void rejectsTrailingBytes() {
    byte[] payload = BridgeProtocol.encode(new BridgeMessage(
        BridgeProtocol.BACKEND_HELLO, "lobby", PlatformKind.PAPER, "", Map.of()));
    byte[] trailing = java.util.Arrays.copyOf(payload, payload.length + 1);

    assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(trailing));
  }

  @Test
  void rejectsDuplicateAttributeKeys() {
    byte[] payload = BridgeProtocolTestPackets.withDuplicateAttribute("online", "1", "2");

    assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(payload));
  }

  @Test
  void rejectsPacketsAboveMinecraftPluginMessageLimit() {
    Map<String, String> attributes = new LinkedHashMap<>();
    for (int index = 0; index < 9; index++) {
      attributes.put("detail-" + index, "x".repeat(4_096));
    }
    BridgeMessage message = new BridgeMessage(
        BridgeProtocol.STATUS_RESPONSE,
        "lobby",
        PlatformKind.PAPER,
        "request-1",
        attributes);

    assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.encode(message));
  }
}
