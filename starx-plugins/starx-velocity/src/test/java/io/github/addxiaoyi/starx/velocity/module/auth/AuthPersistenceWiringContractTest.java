package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthPersistenceWiringContractTest {
  @Test
  void productionUsesJdbcIpSessionsAlongsidePersistentTrustedDevices() throws IOException {
    String plugin = Files.readString(sourceFile(
        "io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java"));
    String module = Files.readString(sourceFile(
        "io/github/addxiaoyi/starx/velocity/module/auth/AuthModule.java"));

    assertTrue(plugin.contains("new io.github.addxiaoyi.starx.common.auth.JdbcIpSessionStore"));
    assertTrue(plugin.contains("trustedDeviceRepository,"));
    assertTrue(plugin.contains("ipSessionStore"));
    assertTrue(module.contains("this.ipSessionStore, this.trustedDeviceRepository"));
  }

  private static Path sourceFile(String relative) {
    Path current = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
      Path source = current.resolve("starx-plugins/starx-velocity/src/main/java").resolve(relative);
      if (Files.isRegularFile(source)) return source;
    }
    throw new IllegalStateException("Velocity source is unavailable: " + relative);
  }
}
