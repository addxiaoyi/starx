package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthCommandsTotpEnrollmentContractTest {
  @Test
  void playerCommandNeverAcceptsSecretsAsArguments() throws IOException {
    String source = Files.readString(sourceFile());

    assertTrue(source.contains("authService.isTotpEnabled(player.getUniqueId())"));
    assertTrue(source.contains("/sx"));
    assertFalse(source.contains("args[1]"));
    assertFalse(source.contains("beginTotpEnrollment("));
    assertFalse(source.contains("confirmTotpEnrollment("));
    assertFalse(source.contains("disableTotp("));
    assertFalse(source.contains("<\u5bc6\u7801>"));
    assertFalse(source.contains("<6\u4f4d\u9a8c\u8bc1\u7801>"));
  }

  private static Path sourceFile() {
    Path current = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
      Path source = current.resolve(
          "starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/"
              + "module/auth/AuthCommands.java");
      if (Files.isRegularFile(source)) return source;
    }
    throw new IllegalStateException("AuthCommands.java source is unavailable");
  }
}
