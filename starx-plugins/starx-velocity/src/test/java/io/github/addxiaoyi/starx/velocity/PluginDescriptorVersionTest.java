package io.github.addxiaoyi.starx.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class PluginDescriptorVersionTest {

  @Test
  void processedDescriptorUsesTheGradleVersionAndNamesUworld() throws Exception {
    String expectedVersion = System.getProperty("starx.project.version");
    assertNotNull(expectedVersion, "starx.project.version");

    try (InputStream stream = PluginDescriptorVersionTest.class
        .getResourceAsStream("/velocity-plugin.json")) {
      assertNotNull(stream, "processed velocity-plugin.json");
      JsonObject descriptor = JsonParser.parseReader(
          new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();

      assertEquals("starx", descriptor.get("id").getAsString());
      assertEquals(expectedVersion, descriptor.get("version").getAsString());
      assertTrue(descriptor.get("description").getAsString().contains("Uworld"));
      assertEquals(
          "io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin",
          descriptor.get("main").getAsString());
    }
  }
}
