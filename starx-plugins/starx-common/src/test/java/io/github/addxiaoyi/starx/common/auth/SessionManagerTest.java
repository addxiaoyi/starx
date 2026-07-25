package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SessionManagerTest {

  @Test
  void concurrentAdmissionNeverExceedsCapacity() throws Exception {
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now, 8);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger opened = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(16)) {
      for (int i = 0; i < 64; i++) {
        pool.submit(() -> {
          start.await();
          if (sessions.open(UUID.randomUUID(), "player", null, AuthLease.create()) != null) {
            opened.incrementAndGet();
          }
          return null;
        });
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
      assertEquals(8, opened.get());
      assertEquals(8, sessions.size());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void rejectsInvalidLifecycleConfiguration() {
    assertThrows(IllegalArgumentException.class,
        () -> new SessionManager(Duration.ZERO, Instant::now));
    assertThrows(IllegalArgumentException.class,
        () -> new SessionManager(Duration.ofMinutes(5), Instant::now, 0));
  }

  @Test
  void authSessionStateCannotBypassExpectedStateTransitions() {
    assertThrows(
        NoSuchMethodException.class,
        () -> AuthSession.class.getDeclaredMethod("setState", AuthSession.State.class));
  }

  @Test
  void authSessionTransitionIsOwnedByTheSessionManagerPackage() throws Exception {
    int modifiers = AuthSession.class
        .getDeclaredMethod("transition", AuthSession.State.class, AuthSession.State.class)
        .getModifiers();

    assertFalse(Modifier.isPublic(modifiers));
  }

  @Test
  void removesOnlyTheExpectedAuthenticationState() {
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    UUID pendingId = UUID.randomUUID();
    UUID authenticatedId = UUID.randomUUID();
    AuthLease pendingLease = AuthLease.create();
    AuthLease authenticatedLease = AuthLease.create();
    try {
      sessions.open(pendingId, "pending", null, pendingLease);
      sessions.open(authenticatedId, "done", null, authenticatedLease);
      assertTrue(sessions.transition(
          pendingId, pendingLease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATING));
      assertTrue(sessions.transition(
          authenticatedId,
          authenticatedLease,
          AuthSession.State.GUEST,
          AuthSession.State.AUTHENTICATED));

      assertTrue(sessions.removeIfState(
          pendingId, pendingLease, AuthSession.State.AUTHENTICATING));
      assertFalse(sessions.removeIfState(
          authenticatedId, authenticatedLease, AuthSession.State.AUTHENTICATING));
      assertTrue(sessions.get(authenticatedId, authenticatedLease).isPresent());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void staleLeaseCannotReadOrRemoveAReplacementSession() {
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    UUID playerId = UUID.randomUUID();
    AuthLease oldLease = AuthLease.create();
    AuthLease replacementLease = AuthLease.create();
    try {
      sessions.open(playerId, "player", null, oldLease);
      assertTrue(sessions.transition(
          playerId, oldLease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATING));
      AuthSession replacement = sessions.open(
          playerId, "player", null, replacementLease);
      assertTrue(sessions.transition(
          playerId,
          replacementLease,
          AuthSession.State.GUEST,
          AuthSession.State.AUTHENTICATING));

      assertTrue(sessions.get(playerId, oldLease).isEmpty());
      assertFalse(sessions.removeIfState(
          playerId, oldLease, AuthSession.State.AUTHENTICATING));
      assertSame(replacement, sessions.get(playerId, replacementLease).orElseThrow());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void stateTransitionRequiresTheExpectedVisibleState() {
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    UUID playerId = UUID.randomUUID();
    AuthLease lease = AuthLease.create();
    try {
      sessions.open(playerId, "player", null, lease);

      assertTrue(sessions.transition(
          playerId, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATING));
      assertFalse(sessions.transition(
          playerId, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATED));
      assertTrue(sessions.transition(
          playerId,
          lease,
          AuthSession.State.AUTHENTICATING,
          AuthSession.State.AUTHENTICATED));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void shutdownClearsSessionsWhenCleanupExecutorFails() throws Exception {
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    FailingShutdownExecutor cleanup = new FailingShutdownExecutor();
    swapCleanupExecutor(sessions, cleanup);
    UUID playerId = UUID.randomUUID();
    AuthLease lease = AuthLease.create();
    sessions.open(playerId, "player", null, lease);

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        sessions::shutdown);

    assertEquals("cleanup shutdown failed", failure.getMessage());
    assertEquals(0, sessions.size());
    assertNull(sessions.open(UUID.randomUUID(), "late", null, AuthLease.create()));
  }

  private static void swapCleanupExecutor(
      SessionManager sessions,
      ScheduledExecutorService replacement
  ) throws Exception {
    Field field = SessionManager.class.getDeclaredField("cleanupExecutor");
    field.setAccessible(true);
    ScheduledExecutorService original = (ScheduledExecutorService) field.get(sessions);
    original.shutdownNow();
    field.set(sessions, replacement);
  }

  private static final class FailingShutdownExecutor extends ScheduledThreadPoolExecutor {
    private FailingShutdownExecutor() {
      super(1);
    }

    @Override
    public List<Runnable> shutdownNow() {
      super.shutdownNow();
      throw new IllegalStateException("cleanup shutdown failed");
    }
  }
}
