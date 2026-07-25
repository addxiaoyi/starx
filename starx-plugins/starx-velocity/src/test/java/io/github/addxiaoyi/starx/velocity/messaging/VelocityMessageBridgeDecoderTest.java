package io.github.addxiaoyi.starx.velocity.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.addxiaoyi.starx.api.messaging.PluginMessage;
import java.util.Map;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class VelocityMessageBridgeDecoderTest {

  @Test
  void decodesValidBackendMessage() throws Exception {
    byte[] packet = packet("plan_stats", "{online=4}");

    var decoded = VelocityMessageBridge.decode(packet);

    assertTrue(decoded.isPresent());
    assertEquals("plan_stats", decoded.orElseThrow().command());
    assertEquals("{online=4}", decoded.orElseThrow().payload().get("data"));
  }

  @Test
  void rejectsNegativeTruncatedAndOversizedPayloads() throws Exception {
    assertTrue(VelocityMessageBridge.decode(packet("sync", -1, new byte[0])).isEmpty());
    assertTrue(VelocityMessageBridge.decode(packet("sync", 5, new byte[1])).isEmpty());
    assertTrue(VelocityMessageBridge.decode(packet("sync", 24_577, new byte[0])).isEmpty());
  }

  @Test
  void outboundEncodingRejectsPayloadAboveBridgeLimit() {
    PluginMessage message = new PluginMessage("sync", Map.of("value", "x".repeat(24_577)));

    assertThrows(IllegalArgumentException.class, () -> VelocityMessageBridge.encode(message));
  }

  private static byte[] packet(String command, String payload) throws Exception {
    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
    return packet(command, body.length, body);
  }

  private static byte[] packet(String command, int length, byte[] body) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeUTF(command);
      out.writeInt(length);
      out.write(body);
    }
    return bytes.toByteArray();
  }
}
