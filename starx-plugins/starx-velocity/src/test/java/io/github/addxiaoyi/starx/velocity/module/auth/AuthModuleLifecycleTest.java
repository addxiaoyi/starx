package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldRuntime;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import io.github.addxiaoyi.starx.uworld.UworldWorldGenerator;
import java.util.Optional;
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

  @Test
  void uworldAvailabilityAllowsTrustedIdentityOnlyMode() {
    assertFalse(AuthModule.hasReadyUworld(null));
    assertFalse(AuthModule.hasReadyUworld(runtime(false)));
    assertTrue(AuthModule.hasReadyUworld(runtime(true)));
  }

  private static UworldRuntime runtime(boolean ready) {
    return new UworldRuntime() {
      @Override
      public boolean isReady() {
        return ready;
      }

      @Override
      public UworldHandle createWorld(
          String owner,
          UworldSpec spec,
          UworldWorldGenerator generator
      ) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<UworldFlowSession> session(
          Player player
      ) {
        return Optional.empty();
      }

      @Override
      public int worldCount() {
        return 0;
      }

      @Override
      public int sessionCount() {
        return 0;
      }
    };
  }
}
