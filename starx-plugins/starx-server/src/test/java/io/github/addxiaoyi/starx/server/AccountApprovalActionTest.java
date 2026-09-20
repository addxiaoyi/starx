package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class AccountApprovalActionTest {
  @Test
  void mapsOnlyExplicitCrossDeviceCommandsToAtomicActions() {
    assertEquals("bind_email", AccountApprovalAction.fromCommand("approve-email").apiName());
    assertEquals("enable_totp", AccountApprovalAction.fromCommand("approve-2fa").apiName());
    assertEquals("bind_skin_account", AccountApprovalAction.fromCommand("approve-skin").apiName());
    assertNull(AccountApprovalAction.fromCommand("approve-delete"));
  }
}
