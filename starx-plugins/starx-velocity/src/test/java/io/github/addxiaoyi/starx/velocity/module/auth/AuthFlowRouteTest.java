package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuthFlowRouteTest {

  @Test
  void pendingPhasesDenyBackendsAndTargetPhaseBindsOneServer() {
    UUID playerId = UUID.randomUUID();
    Object player = new Object();
    Object lobby = new Object();
    AuthFlowIndex<Object, Object, String> flows = new AuthFlowIndex<>();

    assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
        flows.begin(playerId, player, "duplicate"));
    assertTrue(flows.awaitPassword(player));
    assertFalse(flows.allowsBackend(player, lobby));
    assertTrue(flows.awaitTotp(player));
    assertFalse(flows.allowsBackend(player, lobby));
    assertTrue(flows.route(player, lobby));
    assertTrue(flows.allowsBackend(player, lobby));
    assertFalse(flows.allowsBackend(player, new Object()));
  }

  @Test
  void wrongConnectedTargetDeniesAndKeepsTheOwnerUntilDisconnect() {
    UUID playerId = UUID.randomUUID();
    Object player = new Object();
    Object lobby = new Object();
    AuthFlowIndex<Object, Object, String> flows = new AuthFlowIndex<>();

    flows.begin(playerId, player, "duplicate");
    flows.awaitPassword(player);
    flows.route(player, lobby);

    assertEquals(AuthFlowIndex.ConnectResult.WRONG_TARGET, flows.connected(player, new Object()));
    assertTrue(flows.requiresAuth(player));
    assertEquals(AuthFlowIndex.BeginResult.DUPLICATE,
        flows.begin(playerId, new Object(), "duplicate"));
    assertTrue(flows.close(playerId, player));
    assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
        flows.begin(playerId, new Object(), "duplicate"));
  }

  @Test
  void exactConnectedTargetCompletesButKeepsTheOwnerUntilDisconnect() {
    UUID playerId = UUID.randomUUID();
    Object player = new Object();
    Object lobby = new Object();
    AuthFlowIndex<Object, Object, String> flows = new AuthFlowIndex<>();

    flows.begin(playerId, player, "duplicate");
    flows.awaitPassword(player);
    flows.route(player, lobby);

    assertEquals(AuthFlowIndex.ConnectResult.COMPLETED, flows.connected(player, lobby));
    assertFalse(flows.requiresAuth(player));
    assertEquals(AuthFlowIndex.BeginResult.DUPLICATE,
        flows.begin(playerId, new Object(), "duplicate"));
    assertTrue(flows.close(playerId, player));
    assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
        flows.begin(playerId, new Object(), "duplicate"));
  }

  @Test
  void connectedEventBeforeRoutingIsIgnored() {
    UUID playerId = UUID.randomUUID();
    Object player = new Object();
    AuthFlowIndex<Object, Object, String> flows = new AuthFlowIndex<>();

    flows.begin(playerId, player, "duplicate");

    assertEquals(AuthFlowIndex.ConnectResult.IGNORED, flows.connected(player, new Object()));
    assertTrue(flows.requiresAuth(player));
  }
}
