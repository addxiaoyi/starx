package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.auth.CrossDeviceApprovalService;
import io.github.addxiaoyi.starx.common.auth.AuthLease;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CrossDeviceActionExecutorTest {
  private static final UUID PLAYER_ID = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

  @Test
  void bindsWebsiteEmailOnlyWhenPresent() {
    AtomicBoolean called = new AtomicBoolean();
    CrossDeviceActionExecutor executor = new CrossDeviceActionExecutor((uuid, email) -> {
      called.set(uuid.equals(PLAYER_ID) && email.equals("player@example.com"));
      return true;
    }, (uuid, username) -> false);

    assertFalse(executor.execute(challenge(CrossDeviceApprovalService.Action.BIND_EMAIL), " "));
    assertTrue(executor.execute(
        challenge(CrossDeviceApprovalService.Action.BIND_EMAIL), " player@example.com "));
    assertTrue(called.get());
  }

  @Test
  void refreshesWebsiteSkinForTheApprovedPlayer() {
    CrossDeviceActionExecutor executor = new CrossDeviceActionExecutor(
        (username, email) -> false,
        (uuid, username) -> uuid.equals(PLAYER_ID) && username.equals("Alex"));

    assertTrue(executor.execute(
        challenge(CrossDeviceApprovalService.Action.BIND_SKIN_ACCOUNT), "player@example.com"));
  }

  @Test
  void neverEnablesTotpWithoutCodeConfirmation() {
    CrossDeviceActionExecutor executor = new CrossDeviceActionExecutor(
        (username, email) -> true,
        (uuid, username) -> true);

    assertFalse(executor.execute(
        challenge(CrossDeviceApprovalService.Action.ENABLE_TOTP), "player@example.com"));
  }

  @Test
  void approvesOnlyTheLoginLeaseStoredInTheChallenge() {
    AuthLease lease = AuthLease.create();
    AtomicBoolean called = new AtomicBoolean();
    CrossDeviceActionExecutor executor = new CrossDeviceActionExecutor(
        (username, email) -> false,
        (uuid, username) -> false,
        (uuid, approvedLease) -> {
          called.set(uuid.equals(PLAYER_ID) && approvedLease.equals(lease));
          return true;
        });

    assertTrue(executor.execute(
        new CrossDeviceApprovalService.Challenge(
            "token", PLAYER_ID, "Alex",
            CrossDeviceApprovalService.Action.APPROVE_LOGIN,
            Instant.now().plusSeconds(300), lease),
        null));
    assertTrue(called.get());
  }

  private static CrossDeviceApprovalService.Challenge challenge(
      CrossDeviceApprovalService.Action action) {
    return new CrossDeviceApprovalService.Challenge(
        "token", PLAYER_ID, "Alex", action, Instant.now().plusSeconds(300), null);
  }
}
