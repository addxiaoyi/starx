package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.common.auth.AuthLease;
import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.common.auth.AuthSession;
import io.github.addxiaoyi.starx.common.auth.SessionManager;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class AuthLoginBarrierTest {

  @Test
  void starxDenialSurvivesAPluginThatChangesTheResultToAllowed() {
    Object owner = new Object();
    Object duplicate = new Object();
    Component duplicateReason = Component.text("duplicate");
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    UUID playerId = UUID.randomUUID();
    flows.begin(playerId, owner, duplicateReason);
    flows.begin(playerId, duplicate, duplicateReason);

    Optional<Component> enforced = AuthLoginBarrier.enforce(
        flows, duplicate, true, Optional.empty(), Component.text("fallback"));

    assertSame(duplicateReason, enforced.orElseThrow());
    assertTrue(flows.requiresAuth(owner));
  }

  @Test
  void finalExternalDenialIsRetainedOnTheExactConnection() {
    Object player = new Object();
    Component externalReason = Component.text("maintenance");
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    flows.begin(UUID.randomUUID(), player, Component.text("duplicate"));

    Optional<Component> enforced = AuthLoginBarrier.enforce(
        flows, player, false, Optional.of(externalReason), Component.text("fallback"));

    assertSame(externalReason, enforced.orElseThrow());
    assertSame(externalReason, flows.denial(player).orElseThrow());
  }

  @Test
  void finalDenialClosesAnAuthenticatedSessionWithTheExactLease() {
    Object player = new Object();
    Component reason = Component.text("maintenance");
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    UUID playerId = UUID.randomUUID();
    flows.begin(playerId, player, Component.text("duplicate"));
    AuthLease lease = flows.lease(player).orElseThrow();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = authService(sessions);
    try {
      assertTrue(auth.openConnection(lease, playerId, "player", null));
      assertTrue(sessions.transition(
          playerId, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATED));

      Optional<Component> enforced = AuthLoginBarrier.enforceAndClose(
          flows,
          player,
          playerId,
          false,
          Optional.of(reason),
          Component.text("fallback"),
          auth);

      assertSame(reason, enforced.orElseThrow());
      assertTrue(sessions.get(playerId, lease).isEmpty());
      assertTrue(flows.requiresAuth(player));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void finalDenialClosesAGuestSessionWithoutReleasingTheFlowOwner() {
    Object player = new Object();
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    UUID playerId = UUID.randomUUID();
    flows.begin(playerId, player, Component.text("duplicate"));
    AuthLease lease = flows.lease(player).orElseThrow();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = authService(sessions);
    try {
      assertTrue(auth.openConnection(lease, playerId, "player", null));

      AuthLoginBarrier.enforceAndClose(
          flows,
          player,
          playerId,
          false,
          Optional.empty(),
          Component.text("fallback"),
          auth);

      assertTrue(sessions.get(playerId, lease).isEmpty());
      assertTrue(flows.requiresAuth(player));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void finalAllowedResultKeepsTheExactSessionOpen() {
    Object player = new Object();
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    UUID playerId = UUID.randomUUID();
    flows.begin(playerId, player, Component.text("duplicate"));
    AuthLease lease = flows.lease(player).orElseThrow();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = authService(sessions);
    try {
      assertTrue(auth.openConnection(lease, playerId, "player", null));

      Optional<Component> enforced = AuthLoginBarrier.enforceAndClose(
          flows,
          player,
          playerId,
          true,
          Optional.empty(),
          Component.text("fallback"),
          auth);

      assertTrue(enforced.isEmpty());
      assertTrue(sessions.get(playerId, lease).isPresent());
      assertFalse(flows.denial(player).isPresent());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void finalDenialCannotCloseAReplacementSessionOwnedByAnotherLease() {
    Object player = new Object();
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    UUID playerId = UUID.randomUUID();
    flows.begin(playerId, player, Component.text("duplicate"));
    AuthLease staleLease = flows.lease(player).orElseThrow();
    AuthLease replacementLease = AuthLease.create();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = authService(sessions);
    try {
      assertTrue(auth.openConnection(staleLease, playerId, "stale", null));
      assertTrue(auth.openConnection(replacementLease, playerId, "replacement", null));

      AuthLoginBarrier.enforceAndClose(
          flows,
          player,
          playerId,
          false,
          Optional.empty(),
          Component.text("fallback"),
          auth);

      assertTrue(sessions.get(playerId, replacementLease).isPresent());
    } finally {
      sessions.shutdown();
    }
  }

  private static AuthService authService(SessionManager sessions) {
    EventBus events = new EventBus() {
      @Override
      public void publish(StarxEvent event) {
      }

      @Override
      public void subscribe(String type, Consumer<StarxEvent> listener) {
      }
    };
    return new AuthService(new JdbcUserRepository(null), events, sessions);
  }
}
