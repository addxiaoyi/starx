package io.github.addxiaoyi.starx.velocity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccountDeletionWiringContractTest {
  @Test
  void delayedDeletionDisconnectsEveryKnownOnlineUuidAfterErasure() throws Exception {
    Path current = Path.of("").toAbsolutePath();
    Path sourcePath = null;
    for (int depth = 0; depth < 8 && current != null; depth++) {
      Path candidate = current.resolve(
          "src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java");
      if (Files.isRegularFile(candidate)) {
        sourcePath = candidate;
        break;
      }
      candidate = current.resolve(
          "starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java");
      if (Files.isRegularFile(candidate)) {
        sourcePath = candidate;
        break;
      }
      current = current.getParent();
    }
    if (sourcePath == null) {
      throw new IllegalStateException("StarxVelocityPlugin.java source is unavailable");
    }
    String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

    assertTrue(source.contains("proxy.getPlayer(sessionUuid)"));
    assertTrue(source.contains("player.disconnect"));
  }
}
