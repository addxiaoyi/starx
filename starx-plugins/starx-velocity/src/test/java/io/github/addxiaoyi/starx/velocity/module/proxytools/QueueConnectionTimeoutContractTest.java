package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class QueueConnectionTimeoutContractTest {

  @Test
  void everyQueueConnectionIsNonBlockingAndIndividuallyBounded() throws Exception {
    Path proxytools = ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/proxytools");
    assertAsyncModule(proxytools.resolve("QueueModule.java"));
    assertAsyncModule(proxytools.resolve("SmartQueueModule.java"));

    String fifo = Files.readString(proxytools.resolve("queue/QueueService.java"));
    assertTrue(fifo.contains("CompletionStage<Boolean> connect"));
    assertTrue(fifo.contains("result.whenComplete"));
    assertTrue(fifo.contains("ConcurrentHashMap.newKeySet()"));
    assertTrue(fifo.contains("claimNext()"));

    String smart = Files.readString(proxytools.resolve("smart/SmartQueueService.java"));
    assertTrue(smart.contains("CompletionStage<Boolean> connect"));
    assertTrue(smart.contains("result.whenComplete"));
    assertTrue(smart.contains("inFlight"));
    assertTrue(smart.contains("Math.max(0, maxRelease - this.inFlightCount())"));
  }

  private static void assertAsyncModule(Path file) throws Exception {
    String source = Files.readString(file);
    assertTrue(source.contains(".processQueues(this::connect"), file.toString());
    assertTrue(source.contains(
        ".orTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)"), file.toString());
    assertTrue(source.contains("catch (RuntimeException error)"), file.toString());
    assertTrue(source.contains("CompletableFuture.completedFuture(false)"), file.toString());
    assertFalse(source.contains(".join()"), file.toString());
  }
}
