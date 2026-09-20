package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

final class AuthFlowConcurrencyTest {

  @Test
  void exactlyOneOwnerWinsEveryRace() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (int iteration = 0; iteration < 1_000; iteration++) {
        UUID playerId = UUID.randomUUID();
        AuthFlowIndex<Object, Object, String> flows = new AuthFlowIndex<>();
        CountDownLatch start = new CountDownLatch(1);
        Future<AuthFlowIndex.BeginResult> first = executor.submit(() -> {
          start.await();
          return flows.begin(playerId, new Object(), "duplicate");
        });
        Future<AuthFlowIndex.BeginResult> second = executor.submit(() -> {
          start.await();
          return flows.begin(playerId, new Object(), "duplicate");
        });

        start.countDown();
        int accepted = (first.get() == AuthFlowIndex.BeginResult.ACCEPTED ? 1 : 0)
            + (second.get() == AuthFlowIndex.BeginResult.ACCEPTED ? 1 : 0);
        assertEquals(1, accepted, "iteration " + iteration);
      }
    } finally {
      executor.shutdownNow();
    }
  }
}
