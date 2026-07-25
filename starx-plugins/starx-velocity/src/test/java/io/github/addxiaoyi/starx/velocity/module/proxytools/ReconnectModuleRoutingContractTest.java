package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ReconnectModuleRoutingContractTest {

  @Test
  void reconnectSelectsTheInitialServerWithoutStartingASecondConnection() throws Exception {
    Path source = repositoryRoot().resolve(
        "starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/"
            + "module/proxytools/ReconnectModule.java");
    String text = Files.readString(source);

    assertTrue(text.contains("PlayerChooseInitialServerEvent"));
    assertTrue(text.contains("PostOrder.FIRST"));
    assertTrue(text.contains("event::setInitialServer")
        || text.contains("event.setInitialServer"));
    assertFalse(text.contains(
        "import com.velocitypowered.api.event.connection.LoginEvent;"));
    assertFalse(text.contains("createConnectionRequest"));
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("starx-plugins/starx-velocity"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("无法定位 StarX 仓库");
  }
}
