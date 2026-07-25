package io.github.addxiaoyi.starx.velocity.module.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class AnticheatMessageDecoderTest {

  @Test
  void decodesValidDetectionPayload() throws Exception {
    byte[] packet = packet("anticheat:detection", "{\"player\":\"id\",\"check\":\"Speed\"}");

    var decoded = AnticheatModule.decodeDetection(packet);

    assertTrue(decoded.isPresent());
    assertEquals("Speed", decoded.orElseThrow().get("check"));
  }

  @Test
  void rejectsNegativeTruncatedAndOversizedLengths() throws Exception {
    assertTrue(AnticheatModule.decodeDetection(packetWithLength(-1, new byte[0])).isEmpty());
    assertTrue(AnticheatModule.decodeDetection(packetWithLength(20, new byte[2])).isEmpty());
    assertTrue(AnticheatModule.decodeDetection(packetWithLength(24_577, new byte[0])).isEmpty());
  }

  @Test
  void rejectsDetectionWithoutStringIdentityAndCheck() throws Exception {
    assertTrue(AnticheatModule.decodeDetection(packet(
        "anticheat:detection", "{\"player\":4,\"check\":\"Speed\"}")).isEmpty());
    assertTrue(AnticheatModule.decodeDetection(packet(
        "anticheat:detection", "{\"player\":\"id\",\"check\":false}")).isEmpty());
  }

  private static byte[] packet(String command, String json) throws Exception {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    return packet(command, body.length, body);
  }

  private static byte[] packetWithLength(int length, byte[] body) throws Exception {
    return packet("anticheat:detection", length, body);
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
