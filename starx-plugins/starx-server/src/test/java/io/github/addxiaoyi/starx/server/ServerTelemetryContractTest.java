package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ServerTelemetryContractTest {
  @Test
  void defaultConfigDeclaresServerType() throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
      assertNotNull(stream);
      String config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(config.contains("server-type: backend"));
    }
  }

  @Test
  void statusReportsRoutingTelemetry() throws IOException {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/server/StarxServerPlugin.java"));
    assertTrue(source.contains("status.put(\"serverType\""));
    assertTrue(source.contains("status.put(\"memoryPercent\""));
    assertTrue(source.contains("status.put(\"tps\""));
    assertTrue(source.contains("status.put(\"mspt\""));
  }

  @Test
  void numberedNodeIdsShareOneInferredType() {
    assertEquals("survival", StarxServerPlugin.inferServerType("survival-02"));
    assertEquals("lobby", StarxServerPlugin.inferServerType("lobby"));
  }
}
