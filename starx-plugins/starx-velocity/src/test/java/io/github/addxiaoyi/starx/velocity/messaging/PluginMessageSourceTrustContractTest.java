package io.github.addxiaoyi.starx.velocity.messaging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PluginMessageSourceTrustContractTest {
  @Test
  void generalBridgeRejectsPlayerOriginatedPackets() throws Exception {
    assertServerOnly(
        "src/main/java/io/github/addxiaoyi/starx/velocity/messaging/VelocityMessageBridge.java",
        "Optional<PluginMessage> decoded = decode(event.getData());");
  }

  @Test
  void anticheatBridgeRejectsPlayerOriginatedPackets() throws Exception {
    assertServerOnly(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/security/AnticheatModule.java",
        "Optional<Map<String, Object>> decoded = decodeDetection(event.getData());");
  }

  private static void assertServerOnly(String path, String decodeMarker) throws Exception {
    String source = Files.readString(Path.of(path));
    String handled =
        "event.setResult(PluginMessageEvent.ForwardResult.handled());";
    String sourceCheck =
        "event.getSource() instanceof ServerConnection";
    int handledIndex = source.indexOf(handled);
    int sourceIndex = source.indexOf(sourceCheck);
    int decodeIndex = source.indexOf(decodeMarker);
    assertTrue(handledIndex >= 0, path + " must mark the channel as handled");
    assertTrue(
        sourceIndex > handledIndex,
        path + " must check the source after claiming the channel");
    assertTrue(
        decodeIndex > sourceIndex,
        path + " must reject the source before decoding the payload");
  }
}
