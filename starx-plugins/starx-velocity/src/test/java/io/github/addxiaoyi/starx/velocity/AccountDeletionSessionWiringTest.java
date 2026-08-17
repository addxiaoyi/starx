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

    int erase = source.indexOf("accountEraser.eraseAndComplete(");
    int aliases = source.indexOf("knownUuids = deletionKnownMinecraftUuids.get().apply(playerUuid)");
    int logout = source.indexOf("auth.logout(sessionUuid)", erase);
    int bind = source.indexOf("deletionAuth.set(authModule.authService())");
    assertTrue(aliases >= 0 && aliases < erase && logout > erase && bind > logout);
  }
}
