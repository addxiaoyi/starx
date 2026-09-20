package io.github.addxiaoyi.starx.limbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LimboPlayerStateTest {

  @Test
  void firstJoinIsClaimedOnceUnderConcurrency() throws Exception {
    AtomicInteger joins = new AtomicInteger();
    LimboPlayerState<Object, Object, Object, Object> state = new LimboPlayerState<>();
    Object player = new Object();

    runConcurrently(16, () -> state.join(player, joins::incrementAndGet));

    assertEquals(1, joins.get());
    assertTrue(state.isJoined(player));
  }

  @Test
  void failedFirstJoinRollsBackTheClaim() {
    LimboPlayerState<Object, Object, Object, Object> state = new LimboPlayerState<>();
    Object player = new Object();

    assertThrows(IllegalStateException.class, () -> state.join(player, () -> {
      throw new IllegalStateException("join failed");
    }));

    assertFalse(state.isJoined(player));
    assertTrue(state.join(player, () -> { }));
  }

  @Test
  void queueAndCallbackLookupsAreSafe() {
    LimboPlayerState<Object, Object, Object, Object> state = new LimboPlayerState<>();
    Object player = new Object();
    Object queue = new Object();
    Object callback = new Object();

    state.setLoginQueue(player, queue);
    state.setKickCallback(player, callback);

    assertSame(queue, state.loginQueue(player));
    assertSame(callback, state.kickCallback(player));
  }

  @Test
  void takeNextServerConsumesTheTargetAtomically() {
    LimboPlayerState<Object, Object, Object, Object> state = new LimboPlayerState<>();
    Object player = new Object();
    Object server = new Object();

    state.setNextServer(player, server);

    assertSame(server, state.takeNextServer(player));
    assertNull(state.takeNextServer(player));
  }

  private static void runConcurrently(int threads, Runnable action) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < threads; index++) {
        futures.add(executor.submit(() -> {
          ready.countDown();
          start.await();
          action.run();
          return null;
        }));
      }
      ready.await();
      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }
  }
}
