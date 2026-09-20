package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.common.auth.CrossDeviceApprovalService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CrossDeviceApprovalHandlerTest {
  @Test
  void createsWebsiteApprovalLinkForRequestedAction() {
    CrossDeviceApprovalHandler handler = new CrossDeviceApprovalHandler(
        new CrossDeviceApprovalService(), "https://star-web.top");

    Map<String, Object> response = handler.create(
        UUID.randomUUID().toString(), "Alex", "bind_email", "Player@Example.com");

    assertEquals(true, response.get("ok"));
    assertEquals("BIND_EMAIL", response.get("action"));
    org.junit.jupiter.api.Assertions.assertTrue(
        String.valueOf(response.get("url")).startsWith("https://star-web.top/minecraft/approve?token="));
    org.junit.jupiter.api.Assertions.assertTrue(
        String.valueOf(response.get("url")).endsWith("&action=bind_email"));
  }

  @Test
  void rejectsEmailApprovalWithoutTheEmailBoundToTheChallenge() {
    CrossDeviceApprovalHandler handler = new CrossDeviceApprovalHandler(
        new CrossDeviceApprovalService(), "https://star-web.top");

    Map<String, Object> response = handler.create(
        UUID.randomUUID().toString(), "Alex", "bind_email");

    assertEquals(false, response.get("ok"));
    assertEquals("invalid_request", response.get("error"));
  }

  @Test
  void rejectsUnknownActionsWithoutCreatingAChallenge() {
    CrossDeviceApprovalHandler handler = new CrossDeviceApprovalHandler(
        new CrossDeviceApprovalService(), "https://star-web.top");

    Map<String, Object> response = handler.create(
        UUID.randomUUID().toString(), "Alex", "delete_account");

    assertEquals(false, response.get("ok"));
    assertEquals("invalid_action", response.get("error"));
  }

  @Test
  void rejectsTotpBecauseItNeedsAnInGameSixDigitConfirmation() {
    CrossDeviceApprovalHandler handler = new CrossDeviceApprovalHandler(
        new CrossDeviceApprovalService(), "https://star-web.top");

    Map<String, Object> response = handler.create(
        UUID.randomUUID().toString(), "Alex", "enable_totp");

    assertEquals(false, response.get("ok"));
    assertEquals("totp_requires_game_confirmation", response.get("error"));
  }

  @Test
  void rejectsManualLoginChallengeCreation() {
    CrossDeviceApprovalHandler handler = new CrossDeviceApprovalHandler(
        new CrossDeviceApprovalService(), "https://star-web.top");

    Map<String, Object> response = handler.create(
        UUID.randomUUID().toString(), "Alex", "approve_login");

    assertEquals(false, response.get("ok"));
    assertEquals("login_challenge_requires_live_session", response.get("error"));
  }
}
