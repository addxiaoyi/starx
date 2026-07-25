package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AuthModuleLifecycleTest {

  @Test
  void sessionShutdownRunsOnceAndPreservesAnExistingFailure() {
    AtomicInteger calls = new AtomicInteger();
    IllegalStateException existing = new IllegalStateException("existing");

    IllegalStateException failure = AuthModule.stopAuthenticationSessions(
        existing, calls::incrementAndGet);

    assertSame(existing, failure);
    assertEquals(1, calls.get());
  }

  @Test
  void sessionShutdownFailureIsAggregatedWithoutMaskingTheCause() {
    IllegalArgumentException cause = new IllegalArgumentException("stop failed");

    IllegalStateException failure = AuthModule.stopAuthenticationSessions(
        null,
        () -> {
          throw cause;
        });

    assertEquals("One or more authentication resources failed to stop", failure.getMessage());
    assertEquals(1, failure.getSuppressed().length);
    assertEquals(
        "Unable to stop authentication sessions",
        failure.getSuppressed()[0].getMessage());
    assertSame(cause, failure.getSuppressed()[0].getCause());
  }
}
