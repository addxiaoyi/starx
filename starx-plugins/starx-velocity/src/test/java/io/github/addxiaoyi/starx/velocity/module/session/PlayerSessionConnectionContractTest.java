package io.github.addxiaoyi.starx.velocity.module.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class PlayerSessionConnectionContractTest {
  @Test
  void staleDisconnectMustNotFinishTheReplacementConnection() throws Exception {
    String source = Files.readString(ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/session/PlayerSessionModule.java"));

    assertTrue(source.contains("this.activePlayers.compute(playerId, (ignored, current) ->"));
    assertTrue(source.contains("current == player"));
  }

  @Test
  void replacementConnectionMustNotReuseTheOldConnectionsSession() throws Exception {
    String source = Files.readString(ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/session/PlayerSessionModule.java"));

    assertTrue(source.contains("Player previous = this.activePlayers.put(player.getUniqueId(), player);"));
    assertTrue(source.contains("previous != null && previous != player"));
    assertTrue(source.contains("sessions.finish(id, now, DisconnectReason.NORMAL)"));
  }
}
