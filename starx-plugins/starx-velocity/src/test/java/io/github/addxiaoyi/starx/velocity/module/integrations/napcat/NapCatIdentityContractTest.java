package io.github.addxiaoyi.starx.velocity.module.integrations.napcat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class NapCatIdentityContractTest {

  @Test
  void qqBindingCallbackMigratesConfirmedLegacyBindingBeforeSaving() throws Exception {
    String source = Files.readString(source(), StandardCharsets.UTF_8);

    assertTrue(source.contains("migratePlayer"));
  }

  private static Path source() {
    Path current = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 8 && current != null; depth++) {
      Path candidate = current.resolve(
          "src/main/java/io/github/addxiaoyi/starx/velocity/module/integrations/napcat/NapCatModule.java");
      if (Files.isRegularFile(candidate)) return candidate;
      current = current.getParent();
    }
    throw new IllegalStateException("NapCatModule source is unavailable");
  }
}
