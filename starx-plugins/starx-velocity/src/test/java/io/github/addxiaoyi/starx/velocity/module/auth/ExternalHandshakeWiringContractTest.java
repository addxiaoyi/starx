package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ExternalHandshakeWiringContractTest {

  @Test
  void productionAuthenticationReadsTheRawHandshakeBeforePasswordAdmission() throws IOException {
    String plugin = Files.readString(source("StarxVelocityPlugin.java"));
    String auth = Files.readString(source("module/auth/AuthModule.java"));

    assertTrue(plugin.contains("ExternalHandshake.open(this.dataDirectory)"));
    assertTrue(plugin.contains("externalHandshake"));
    assertTrue(auth.contains("ExternalHandshake externalHandshake"));
    assertTrue(auth.contains("this.externalHandshake.matches(player.getRawVirtualHost().orElse(null))"));
    assertTrue(auth.contains("\"external-handshake\""));
    assertTrue(auth.contains("autoLoginTrusted"));
  }

  private static Path source(String file) {
    Path current = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
      Path candidate = current.resolve(
          "starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/" + file);
      if (Files.isRegularFile(candidate)) return candidate;
    }
    throw new IllegalStateException("Velocity source is unavailable: " + file);
  }
}
