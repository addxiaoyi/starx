package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuthFlowIndexTest {

  @Test
  void duplicateUuidCannotReplaceTheOwner() {
    UUID playerId = UUID.randomUUID();
    Object first = new Object();
    Object duplicate = new Object();
    AuthFlowIndex<Object, String, String> flows = new AuthFlowIndex<>();

    assertEquals(AuthFlowIndex.BeginResult.ACCEPTED, flows.begin(playerId, first, "duplicate"));
    assertEquals(AuthFlowIndex.BeginResult.DUPLICATE, flows.begin(playerId, duplicate, "duplicate"));
    assertTrue(flows.requiresAuth(first));
    assertEquals(AuthFlowIndex.Phase.DENIED, flows.phase(duplicate).orElseThrow());
    assertEquals("duplicate", flows.denial(duplicate).orElseThrow());
    assertFalse(flows.close(playerId, duplicate));
    assertTrue(flows.requiresAuth(first));
  }

  @Test
  void staleCloseCannotReleaseReplacementOwner() {
    UUID playerId = UUID.randomUUID();
    Object first = new Object();
    Object replacement = new Object();
    AuthFlowIndex<Object, String, String> flows = new AuthFlowIndex<>();

    assertEquals(AuthFlowIndex.BeginResult.ACCEPTED, flows.begin(playerId, first, "duplicate"));
    assertTrue(flows.close(playerId, first));
    assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
        flows.begin(playerId, replacement, "duplicate"));
    assertFalse(flows.close(playerId, first));
    assertTrue(flows.requiresAuth(replacement));
  }

  @Test
  void inputIsAcceptedOnlyDuringCredentialPhases() {
    UUID playerId = UUID.randomUUID();
    Object player = new Object();
    AuthFlowIndex<Object, Object, String> flows = new AuthFlowIndex<>();

    assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
        flows.begin(playerId, player, "duplicate"));
    assertFalse(flows.requiresInput(player));
    assertTrue(flows.awaitPassword(player));
    assertTrue(flows.requiresInput(player));
    assertTrue(flows.awaitTotp(player));
    assertTrue(flows.requiresInput(player));
    assertTrue(flows.route(player, new Object()));
    assertFalse(flows.requiresInput(player));
  }

  @Test
  void credentialVerificationIsSingleFlightAndCanRetry() {
    UUID playerId = UUID.randomUUID();
    Object player = new Object();
    AuthFlowIndex<Object, Object, String> flows = new AuthFlowIndex<>();
    flows.begin(playerId, player, "duplicate");
    flows.awaitPassword(player);

    assertEquals(
        AuthFlowIndex.InputType.PASSWORD,
        flows.claimInput(player).orElseThrow());
    assertTrue(flows.claimInput(player).isEmpty());
    assertFalse(flows.requiresInput(player));
    assertTrue(flows.retryInput(player, AuthFlowIndex.InputType.PASSWORD));
    assertTrue(flows.requiresInput(player));

    assertEquals(
        AuthFlowIndex.InputType.PASSWORD,
        flows.claimInput(player).orElseThrow());
    assertTrue(flows.awaitTotp(player));
    assertEquals(
        AuthFlowIndex.InputType.TOTP,
        flows.claimInput(player).orElseThrow());
    assertTrue(flows.route(player, new Object()));
  }

  @Test
  void denialKeepsTheOwnerAndReasonUntilExactDisconnect() {
    UUID playerId = UUID.randomUUID();
    Object player = new Object();
    Object duplicate = new Object();
    AuthFlowIndex<Object, Object, String> flows = new AuthFlowIndex<>();

    assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
        flows.begin(playerId, player, "duplicate"));
    assertTrue(flows.deny(player, "admission failed"));
    assertFalse(flows.deny(player));
    assertTrue(flows.requiresAuth(player));
    assertEquals("admission failed", flows.denial(player).orElseThrow());
    assertEquals(AuthFlowIndex.BeginResult.DUPLICATE,
        flows.begin(playerId, duplicate, "duplicate"));
    assertTrue(flows.close(playerId, player));
    assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
        flows.begin(playerId, new Object(), "duplicate"));
  }

  @Test
  void acceptedFlowExposesOneConnectionLease() {
    UUID playerId = UUID.randomUUID();
    Object player = new Object();
    AuthFlowIndex<Object, Object, String> flows = new AuthFlowIndex<>();

    assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
        flows.begin(playerId, player, "duplicate"));
    assertEquals(flows.lease(player).orElseThrow(), flows.lease(player).orElseThrow());
  }
}
