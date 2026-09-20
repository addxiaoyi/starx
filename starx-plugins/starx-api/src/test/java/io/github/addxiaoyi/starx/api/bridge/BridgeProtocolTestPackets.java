package io.github.addxiaoyi.starx.api.bridge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class BridgeProtocolTestPackets {

  private BridgeProtocolTestPackets() {
  }

  static byte[] withDuplicateAttribute(String key, String first, String second) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.write("STARX".getBytes(StandardCharsets.US_ASCII));
      out.writeByte(1);
      writeString(out, "backend.status.response");
      writeString(out, "lobby");
      out.writeByte(2);
      writeString(out, "request-1");
      out.writeShort(2);
      writeString(out, key);
      writeString(out, first);
      writeString(out, key);
      writeString(out, second);
      out.flush();
      return bytes.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Unable to create protocol test packet", error);
    }
  }

  private static void writeString(DataOutputStream out, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    out.writeShort(encoded.length);
    out.write(encoded);
  }
}
