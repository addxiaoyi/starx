package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class ConnectionLifecycleRaceContractTest {
  @Test
  void onlineSyncKeepsReplacementConnectionState() throws Exception {
    String source = Files.readString(ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/proxytools/OnlineSyncModule.java"));
    assertTrue(source.contains("this.activePlayers.compute(playerId, (ignored, current) ->"));
    assertTrue(source.contains("current == player"));
  }

  @Test
  void forgeCompatKeepsReplacementConnectionState() throws Exception {
    String source = Files.readString(ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/proxytools/ForgeCompatModule.java"));
    assertTrue(source.contains("this.activePlayers.compute(playerId, (ignored, current) ->"));
    assertTrue(source.contains("current == player"));
  }
}
