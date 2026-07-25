package io.github.addxiaoyi.starx.velocity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccountDeletionSessionWiringTest {
  @Test
  void successfulErasureImmediatelyRevokesAuthenticationSession() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java"));

    int erase = source.indexOf("accountEraser.erase(playerUuid, erasedAt)");
    int logout = source.indexOf("auth.logout(playerUuid)", erase);
    int bind = source.indexOf("deletionAuth.set(authModule.authService())");
    assertTrue(erase >= 0 && logout > erase && bind > logout);
  }
}
