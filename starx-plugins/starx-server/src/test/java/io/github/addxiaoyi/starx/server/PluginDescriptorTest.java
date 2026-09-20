package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class PluginDescriptorTest {

  @Test
  void descriptorSupportsPaperAndFolia() throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
      assertNotNull(stream, "plugin.yml must be packaged");
      String descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

      assertTrue(descriptor.contains("main: io.github.addxiaoyi.starx.server.StarxServerPlugin"));
      assertTrue(descriptor.contains("api-version: '1.21'"));
      assertTrue(descriptor.contains("folia-supported: true"));
      assertTrue(descriptor.contains("softdepend: [PlaceholderAPI, SkinsRestorer]"));
      assertFalse(descriptor.contains("load: STARTUP"));
      assertTrue(descriptor.contains("starx.command.server"));
    }
  }
}
