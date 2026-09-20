package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JdbcUserRepositoryUuidBindingContractTest {
  @Test
  void bindsVarcharUuidsAsStrings() throws IOException {
    String source = Files.readString(sourceFile());

    assertFalse(source.matches("(?s).*setObject\\([^,]+,\\s*(uuid|user\\.uuid\\(\\)).*"));
  }

  private static Path sourceFile() {
    Path current = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
      Path source = current.resolve(
          "starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/database/"
              + "JdbcUserRepository.java");
      if (Files.isRegularFile(source)) return source;
    }
    throw new IllegalStateException("JdbcUserRepository.java source is unavailable");
  }
}
