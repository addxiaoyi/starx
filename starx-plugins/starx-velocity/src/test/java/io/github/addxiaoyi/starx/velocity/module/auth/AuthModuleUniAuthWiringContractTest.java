package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthModuleUniAuthWiringContractTest {
  @Test
  void productionAuthServiceReceivesConfiguredUniAuthBridge() throws Exception {
    Path source = locateSource();
    String text = Files.readString(source, StandardCharsets.UTF_8);

    assertTrue(text.contains("new UniAuthBridge("));
    assertTrue(text.contains("this.uniauthConfig.enabled() && this.uniauthConfig.bridgeMode()"));
    assertTrue(text.contains("this.uniauthConfig"));
    assertTrue(text.contains("uniAuthBridge"));
    assertTrue(text.contains("this.trustedDeviceRepository"));
  }

  private static Path locateSource() {
    Path current = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 8 && current != null; depth++) {
      Path candidate = current.resolve(
          "starx-plugins/starx-velocity/src/main/java/"
              + "io/github/addxiaoyi/starx/velocity/module/auth/AuthModule.java");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("AuthModule.java source is unavailable");
  }
}
