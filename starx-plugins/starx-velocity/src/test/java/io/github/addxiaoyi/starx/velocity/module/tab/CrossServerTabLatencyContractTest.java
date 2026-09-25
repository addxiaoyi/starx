package io.github.addxiaoyi.starx.velocity.module.tab;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CrossServerTabLatencyContractTest {
  @Test
  void reusesTheSharedSmoothedVelocityPingSnapshot() throws Exception {
    Path sourcePath = ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/tab/CrossServerTabModule.java");
    String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

    assertTrue(source.contains("PlayerLatencyTracker latencyTracker"));
    assertTrue(source.contains("latencyTracker.snapshot(player.getUniqueId())"));
    assertTrue(source.contains("snapshot.smoothedPing()"));
    assertFalse(source.contains("return ping < 0 || ping > Integer.MAX_VALUE"));
  }

  @Test
  void carriesGameProfilesInTheRosterInsteadOfLookingUpEveryViewerTargetPair() throws Exception {
    Path sourcePath = ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/tab/CrossServerTabModule.java");
    String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

    assertTrue(source.contains("player.getGameProfile()"));
    assertTrue(source.contains("state.gameProfile()"));
    assertFalse(source.contains("proxy().getPlayer(targetId)"));
  }
}
