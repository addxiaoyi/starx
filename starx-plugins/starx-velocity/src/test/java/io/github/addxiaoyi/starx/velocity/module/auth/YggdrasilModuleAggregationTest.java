package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class YggdrasilModuleAggregationTest {
  @Test
  void allServersRequiresEveryConfiguredServerToAcceptTheUuid() {
    assertTrue(YggdrasilModule.allSuccessful(List.of(
        CompletableFuture.completedFuture(true),
        CompletableFuture.completedFuture(true))).join());
    assertFalse(YggdrasilModule.allSuccessful(List.of(
        CompletableFuture.completedFuture(true),
        CompletableFuture.completedFuture(false))).join());
  }

  @Test
  void failedServerCheckFailsClosed() {
    CompletableFuture<Boolean> failed = new CompletableFuture<>();
    failed.completeExceptionally(new IllegalStateException("server unavailable"));

    assertFalse(YggdrasilModule.allSuccessful(List.of(failed)).join());
  }
}
