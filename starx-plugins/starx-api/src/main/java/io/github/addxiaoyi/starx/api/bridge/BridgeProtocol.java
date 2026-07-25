package io.github.addxiaoyi.starx.api.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Constants and deterministic codec for the bounded {@value #CHANNEL} bridge protocol.
 */
public final class BridgeProtocol {
  /** Minecraft plugin-message channel used by the bridge. */
  public static final String CHANNEL = "starx:bridge";
  /** Proxy handshake message type. */
  public static final String PROXY_HELLO = "proxy.hello";
  /** Backend handshake message type. */
  public static final String BACKEND_HELLO = "backend.hello";
  /** Proxy-to-backend status request type. */
  public static final String STATUS_REQUEST = "proxy.status.request";
  /** Backend-to-proxy status response type. */
  public static final String STATUS_RESPONSE = "backend.status.response";
  /** Proxy-to-backend skin request type. */
  public static final String SKIN_REQUEST = "proxy.skin.request";
  /** Backend-to-proxy skin response type. */
  public static final String SKIN_RESPONSE = "backend.skin.response";
  /** Proxy-to-backend configuration synchronization type. */
  public static final String CONFIG_SYNC = "proxy.config.sync";
  /** Current wire protocol version. */
  public static final int VERSION = 1;
  /** Maximum encoded packet size accepted by the codec. */
  public static final int MAX_PACKET_BYTES = 32_766;

  private static final byte[] MAGIC = "STARX".getBytes(StandardCharsets.US_ASCII);
  static final int MAX_ATTRIBUTES = 32;
  static final int MAX_TYPE_BYTES = 96;
  static final int MAX_NODE_ID_BYTES = 128;
  static final int MAX_CORRELATION_BYTES = 128;
  static final int MAX_ATTRIBUTE_KEY_BYTES = 64;
  static final int MAX_ATTRIBUTE_VALUE_BYTES = 4_096;

  private BridgeProtocol() {
  }

  static void validate(BridgeMessage message) {
    validate(message.type(), message.nodeId(), message.correlationId(), message.attributes());
  }

  static void validate(
      String type,
      String nodeId,
      String correlationId,
      Map<String, String> attributes
  ) {
    requireUtf8Limit(type, MAX_TYPE_BYTES, "type");
    requireUtf8Limit(nodeId, MAX_NODE_ID_BYTES, "nodeId");
    requireUtf8Limit(correlationId, MAX_CORRELATION_BYTES, "correlationId");
    if (attributes.size() > MAX_ATTRIBUTES) {
      throw new IllegalArgumentException(
          "Bridge message has too many attributes: " + attributes.size());
    }
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      requireUtf8Limit(entry.getKey(), MAX_ATTRIBUTE_KEY_BYTES, "attribute key");
      requireUtf8Limit(entry.getValue(), MAX_ATTRIBUTE_VALUE_BYTES, "attribute value");
    }
  }

  /**
   * Encodes a validated message using the current wire version.
   *
   * @param message message to encode
   * @return encoded packet bytes
   * @throws IllegalArgumentException if a field or packet exceeds protocol bounds
   */
  public static byte[] encode(BridgeMessage message) {
    Objects.requireNonNull(message, "message");
    validate(message);

    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.write(MAGIC);
      out.writeByte(VERSION);
      writeString(out, message.type(), MAX_TYPE_BYTES, "type");
      writeString(out, message.nodeId(), MAX_NODE_ID_BYTES, "nodeId");
      out.writeByte(message.platform().wireId());
      writeString(out, message.correlationId(), MAX_CORRELATION_BYTES, "correlationId");
      Map<String, String> sorted = new TreeMap<>(message.attributes());
      out.writeShort(sorted.size());
      for (Map.Entry<String, String> entry : sorted.entrySet()) {
        writeString(out, entry.getKey(), MAX_ATTRIBUTE_KEY_BYTES, "attribute key");
        writeString(out, entry.getValue(), MAX_ATTRIBUTE_VALUE_BYTES, "attribute value");
      }
      out.flush();
      byte[] encoded = bytes.toByteArray();
      if (encoded.length > MAX_PACKET_BYTES) {
        throw new IllegalArgumentException(
            "Bridge packet exceeds " + MAX_PACKET_BYTES + " bytes: " + encoded.length);
      }
      return encoded;
    } catch (IOException error) {
      throw new IllegalStateException("Unable to encode StarX bridge message", error);
    }
  }

  /**
   * Decodes and validates one bridge packet.
   *
   * @param payload encoded packet bytes
   * @return decoded immutable message
   * @throws IllegalArgumentException if the packet is malformed, unsupported, or out of bounds
   */
  public static BridgeMessage decode(byte[] payload) {
    Objects.requireNonNull(payload, "payload");
    if (payload.length == 0 || payload.length > MAX_PACKET_BYTES) {
      throw new IllegalArgumentException("Invalid StarX bridge packet size: " + payload.length);
    }

    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
      byte[] magic = in.readNBytes(MAGIC.length);
      if (!java.util.Arrays.equals(MAGIC, magic)) {
        throw new IllegalArgumentException("Invalid StarX bridge packet magic");
      }
      int version = in.readUnsignedByte();
      if (version != VERSION) {
        throw new IllegalArgumentException("Unsupported StarX bridge protocol version: " + version);
      }
      String type = readString(in, MAX_TYPE_BYTES, "type");
      String nodeId = readString(in, MAX_NODE_ID_BYTES, "nodeId");
      PlatformKind platform = PlatformKind.fromWireId(in.readUnsignedByte());
      String correlationId = readString(in, MAX_CORRELATION_BYTES, "correlationId");
      int attributeCount = in.readUnsignedShort();
      if (attributeCount > MAX_ATTRIBUTES) {
        throw new IllegalArgumentException(
            "Bridge message has too many attributes: " + attributeCount);
      }
      Map<String, String> attributes = new LinkedHashMap<>();
      for (int index = 0; index < attributeCount; index++) {
        String key = readString(in, MAX_ATTRIBUTE_KEY_BYTES, "attribute key");
        String value = readString(in, MAX_ATTRIBUTE_VALUE_BYTES, "attribute value");
        if (attributes.putIfAbsent(key, value) != null) {
          throw new IllegalArgumentException("Duplicate StarX bridge attribute: " + key);
        }
      }
      if (in.available() != 0) {
        throw new IllegalArgumentException(
            "StarX bridge packet contains " + in.available() + " trailing bytes");
      }
      return new BridgeMessage(type, nodeId, platform, correlationId, attributes);
    } catch (EOFException error) {
      throw new IllegalArgumentException("Truncated StarX bridge packet", error);
    } catch (IOException error) {
      throw new IllegalArgumentException("Unable to decode StarX bridge packet", error);
    }
  }

  private static void writeString(
      DataOutputStream out,
      String value,
      int limit,
      String label
  ) throws IOException {
    byte[] encoded = requireUtf8Limit(value, limit, label);
    out.writeShort(encoded.length);
    out.write(encoded);
  }

  private static byte[] requireUtf8Limit(String value, int limit, String label) {
    Objects.requireNonNull(value, label);
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length > limit) {
      throw new IllegalArgumentException(
          "Bridge " + label + " exceeds " + limit + " bytes: " + encoded.length);
    }
    return encoded;
  }

  private static String readString(
      DataInputStream in,
      int limit,
      String label
  ) throws IOException {
    int length = in.readUnsignedShort();
    if (length > limit) {
      throw new IllegalArgumentException(
          "Bridge " + label + " exceeds " + limit + " bytes: " + length);
    }
    byte[] encoded = in.readNBytes(length);
    if (encoded.length != length) {
      throw new EOFException("Truncated bridge " + label);
    }
    return new String(encoded, StandardCharsets.UTF_8);
  }
}
