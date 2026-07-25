package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class QueueConnectionTimeoutContractTest {

  @Test
  void everyQueueConnectionHasABoundedWait() throws Exception {
    Path source = ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/proxytools");
    assertTimeout(source.resolve("QueueModule.java"));
    assertTimeout(source.resolve("SmartQueueModule.java"));
  }

  private static void assertTimeout(Path file) throws Exception {
    String source = Files.readString(file);
    assertTrue(source.contains(".orTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)"),
        file.toString());
  }
}
