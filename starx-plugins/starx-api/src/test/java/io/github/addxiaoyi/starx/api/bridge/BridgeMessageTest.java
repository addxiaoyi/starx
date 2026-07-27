package io.github.addxiaoyi.starx.api.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BridgeMessageTest {

  @Test
  void rejectsFieldsAboveTheirUtf8Limits() {
    assertThrows(IllegalArgumentException.class, () -> message("类".repeat(33), "node", "id", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> message("type", "服".repeat(43), "id", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> message("type", "node", "关".repeat(43), Map.of()));
  }

  @Test
  void rejectsTooManyAttributes() {
    Map<String, String> attributes = new LinkedHashMap<>();
    for (int index = 0; index < 33; index++) {
      attributes.put("key-" + index, "value");
    }

    assertThrows(
        IllegalArgumentException.class,
        () -> message("type", "node", "id", attributes));
  }

  @Test
  void rejectsOversizedAttributeKeysAndValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> message("type", "node", "id", Map.of("键".repeat(22), "value")));
    assertThrows(
        IllegalArgumentException.class,
        () -> message("type", "node", "id", Map.of("key", "值".repeat(1_366))));
  }

  @Test
  void createsPersistentSkinUpdateWithoutRequiringASignature() {
    BridgeMessage update = BridgeMessage.skinUpdate(
        "proxy",
        "skin-update-1",
        "4f06bce0-32d7-4d4d-bb17-9f7e92ae8701",
        "Alex",
        "encoded-texture",
        "");

    assertEquals(BridgeProtocol.SKIN_UPDATE, update.type());
    assertEquals("encoded-texture", update.attributes().get("value"));
    assertEquals("", update.attributes().get("signature"));
  }

  @Test
  void acceptsChineseFieldsWithinUtf8Limits() {
    BridgeMessage message = message(
        "后端.状态",
        "生存服",
        "请求-一",
        Map.of("状态", "运行中"));

    assertEquals("运行中", message.attributes().get("状态"));
  }

  private static BridgeMessage message(
      String type,
      String nodeId,
      String correlationId,
      Map<String, String> attributes
  ) {
    return new BridgeMessage(type, nodeId, PlatformKind.PAPER, correlationId, attributes);
  }
}
