package io.github.addxiaoyi.starx.api.bridge;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable message exchanged on the StarX bridge channel.
 *
 * @param type protocol message type
 * @param nodeId stable sender or target node identifier
 * @param platform platform that created the message
 * @param correlationId request correlation identifier, or an empty string
 * @param attributes immutable string attributes
 */
public record BridgeMessage(
    String type,
    String nodeId,
    PlatformKind platform,
    String correlationId,
    Map<String, String> attributes
) {

  /**
   * Validates and creates a bridge message.
   *
   * @param type protocol message type
   * @param nodeId stable node identifier
   * @param platform source platform
   * @param correlationId request correlation identifier
   * @param attributes message attributes
   */
  public BridgeMessage {
    type = requireText(type, "type");
    nodeId = requireText(nodeId, "nodeId");
    platform = Objects.requireNonNull(platform, "platform");
    correlationId = correlationId == null ? "" : correlationId;
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    BridgeProtocol.validate(type, nodeId, correlationId, attributes);
  }

  /**
   * Creates a proxy or backend handshake message.
   *
   * @param nodeId sender node identifier
   * @param platform sender platform
   * @return handshake message
   */
  public static BridgeMessage hello(String nodeId, PlatformKind platform) {
    String type = platform == PlatformKind.VELOCITY
        ? BridgeProtocol.PROXY_HELLO
        : BridgeProtocol.BACKEND_HELLO;
    return new BridgeMessage(type, nodeId, platform, "", Map.of());
  }

  /**
   * Creates a backend status request.
   *
   * @param nodeId requesting proxy node identifier
   * @param correlationId request correlation identifier
   * @return status request message
   */
  public static BridgeMessage statusRequest(String nodeId, String correlationId) {
    return new BridgeMessage(
        BridgeProtocol.STATUS_REQUEST,
        nodeId,
        PlatformKind.VELOCITY,
        correlationId,
        Map.of());
  }

  /**
   * Creates a backend status response.
   *
   * @param nodeId backend node identifier
   * @param platform backend platform
   * @param correlationId matching request identifier
   * @param attributes reported status attributes
   * @return status response message
   */
  public static BridgeMessage statusResponse(
      String nodeId,
      PlatformKind platform,
      String correlationId,
      Map<String, String> attributes
  ) {
    return new BridgeMessage(
        BridgeProtocol.STATUS_RESPONSE,
        nodeId,
        platform,
        correlationId,
        attributes);
  }

  /**
   * Creates a signed-skin lookup request.
   *
   * @param nodeId requesting proxy node identifier
   * @param correlationId request correlation identifier
   * @param uuid player UUID text
   * @param name player name
   * @return skin request message
   */
  public static BridgeMessage skinRequest(
      String nodeId,
      String correlationId,
      String uuid,
      String name
  ) {
    return new BridgeMessage(
        BridgeProtocol.SKIN_REQUEST,
        nodeId,
        PlatformKind.VELOCITY,
        correlationId,
        Map.of("uuid", requireText(uuid, "uuid"), "name", requireText(name, "name")));
  }

  /**
   * Creates a signed-skin lookup response.
   *
   * @param nodeId backend node identifier
   * @param platform backend platform
   * @param correlationId matching request identifier
   * @param attributes skin response attributes
   * @return skin response message
   */
  public static BridgeMessage skinResponse(
      String nodeId,
      PlatformKind platform,
      String correlationId,
      Map<String, String> attributes
  ) {
    return new BridgeMessage(
        BridgeProtocol.SKIN_RESPONSE, nodeId, platform, correlationId, attributes);
  }

  /**
   * Creates a maintenance-mode synchronization message.
   *
   * @param nodeId proxy node identifier
   * @param correlationId synchronization correlation identifier
   * @param enabled maintenance-mode state
   * @return configuration synchronization message
   */
  public static BridgeMessage maintenanceConfig(
      String nodeId,
      String correlationId,
      boolean enabled
  ) {
    return new BridgeMessage(
        BridgeProtocol.CONFIG_SYNC,
        nodeId,
        PlatformKind.VELOCITY,
        correlationId,
        Map.of("maintenance", Boolean.toString(enabled)));
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Bridge message " + label + " must not be blank");
    }
    return value;
  }

}
