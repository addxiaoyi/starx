package io.github.addxiaoyi.starx.velocity.module.integrations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class MapModCrossServerContractTest {

  @Test
  void mapCompatibilityNeverInjectsAnEmptyPrivateProtocolPacket() throws Exception {
    String source = Files.readString(ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/integrations/MapModIntegrationModule.java"));

    assertFalse(source.contains("sendPluginMessage"));
    assertFalse(source.contains("registerChannel"));
    assertTrue(source.contains("MapIntegrationCatalog.detectClientMaps"));
  }
}
