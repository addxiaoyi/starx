package io.github.addxiaoyi.starx.velocity.module.integrations;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class MapConnectionLifecycleRaceContractTest {
  @Test
  void mapDetectionKeepsReplacementConnectionState() throws Exception {
    String source = Files.readString(ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/integrations/MapModIntegrationModule.java"));
    assertTrue(source.contains("this.activePlayers.compute(playerId, (ignored, current) ->"));
    assertTrue(source.contains("current == player"));
  }

  @Test
  void replacementWithoutMapModsMustClearThePreviousConnectionsDetection() throws Exception {
    String source = Files.readString(ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/integrations/MapModIntegrationModule.java"));
    assertTrue(source.contains("this.detected.remove(player.getUniqueId());"));
  }
}
