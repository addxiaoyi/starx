package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HttpExecutorBackpressureContractTest {
  @Test
  void saturatedHttpPoolAppliesBackpressureInsteadOfRejectingConnections() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java"));

    assertTrue(source.contains("new ThreadPoolExecutor.CallerRunsPolicy()"));
    assertFalse(source.contains("new ThreadPoolExecutor.AbortPolicy()"));
    assertTrue(source.contains("new ArrayBlockingQueue<>(256)"));
  }
}
