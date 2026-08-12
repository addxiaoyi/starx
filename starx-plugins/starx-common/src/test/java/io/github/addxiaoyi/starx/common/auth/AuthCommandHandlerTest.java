package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuthCommandHandlerTest {

  @Test
  void credentialsNeverUseAStaleSecondFactorSession() throws Exception {
    FakeOperations auth = new FakeOperations();
    auth.registered = true;
    AuthCommandHandler handler = new AuthCommandHandler(auth);
    AuthLease lease = AuthLease.create();

    handler.handleCredentials(
        lease,
        UUID.randomUUID(),
        "Player",
        "CaseSensitivePassword",
        InetAddress.getLoopbackAddress(),
        "device");

    assertEquals(lease, auth.lease);
    assertEquals("CaseSensitivePassword", auth.password);
    assertTrue(auth.loginCalled);
    assertFalse(auth.totpCalled);
    assertFalse(auth.recoveryCalled);
  }

  @Test
  void commandAliasesPreserveTheCredentialCase() {
    FakeOperations auth = new FakeOperations();
    auth.registered = true;
    AuthCommandHandler handler = new AuthCommandHandler(auth);

    handler.handleCredentials(
        AuthLease.create(), UUID.randomUUID(), "Player", "/login AbC123_X", null, null);

    assertEquals("AbC123_X", auth.password);
  }

  @Test
  void explicitSecondFactorChoosesTotpOrRecoveryCode() {
    FakeOperations auth = new FakeOperations();
    AuthCommandHandler handler = new AuthCommandHandler(auth);
    UUID playerId = UUID.randomUUID();
    AuthLease lease = AuthLease.create();

    handler.handleSecondFactor(lease, playerId, "123456");
    assertTrue(auth.totpCalled);
    assertEquals("123456", auth.code);
    assertEquals(lease, auth.lease);

    auth.totpCalled = false;
    handler.handleSecondFactor(lease, playerId, "A1B2C3D4E5");
    assertTrue(auth.recoveryCalled);
    assertFalse(auth.totpCalled);
  }

  @Test
  void blankInputsFailWithoutCallingAuthentication() {
    FakeOperations auth = new FakeOperations();
    AuthCommandHandler handler = new AuthCommandHandler(auth);
    AuthLease lease = AuthLease.create();

    AuthResult credentials = handler.handleCredentials(
        lease, UUID.randomUUID(), "Player", "  ", null, null);
    AuthResult secondFactor = handler.handleSecondFactor(lease, UUID.randomUUID(), null);

    assertFalse(credentials.success());
    assertFalse(secondFactor.success());
    assertFalse(auth.loginCalled);
    assertFalse(auth.totpCalled);
  }

  @Test
  void websiteLoginLetsTheServiceDistinguishExpiredSessionsFromUnregisteredUsers() {
    FakeOperations auth = new FakeOperations();
    auth.registered = false;
    auth.webApprovalResult = AuthResult.failure("认证会话已过期，请重新连接。");
    AuthCommandHandler handler = new AuthCommandHandler(auth);
    AuthLease lease = AuthLease.create();

    AuthResult result = handler.handleCredentials(
        lease,
        UUID.randomUUID(),
        "Player",
        "/login web",
        null,
        null);

    assertFalse(result.success());
    assertEquals("认证会话已过期，请重新连接。", result.message());
    assertTrue(auth.webApprovalCalled);
  }

  private static final class FakeOperations implements AuthCommandHandler.AuthOperations {
    private boolean registered;
    private boolean loginCalled;
    private boolean totpCalled;
    private boolean recoveryCalled;
    private boolean webApprovalCalled;
    private String password;
    private String code;
    private AuthLease lease;
    private AuthResult webApprovalResult = AuthResult.failure("网页登录确认当前不可用，请稍后重试");

    @Override
    public boolean isUserRegistered(UUID playerId) {
      return this.registered;
    }

    @Override
    public AuthResult login(
        AuthLease lease,
        UUID playerId,
        String username,
        String password,
        InetAddress address,
        String deviceId
    ) {
      this.loginCalled = true;
      this.lease = lease;
      this.password = password;
      return AuthResult.success("logged in");
    }

    @Override
    public AuthResult register(
        AuthLease lease,
        UUID playerId,
        String username,
        String password
    ) {
      this.lease = lease;
      this.password = password;
      return AuthResult.success("registered");
    }

    @Override
    public AuthResult requestWebLoginApproval(
        AuthLease lease,
        UUID playerId,
        String username
    ) {
      this.webApprovalCalled = true;
      return this.webApprovalResult;
    }

    @Override
    public AuthResult verifyTotp(AuthLease lease, UUID playerId, String code) {
      this.totpCalled = true;
      this.lease = lease;
      this.code = code;
      return AuthResult.success("totp");
    }

    @Override
    public AuthResult verifyRecoveryCode(AuthLease lease, UUID playerId, String code) {
      this.recoveryCalled = true;
      this.lease = lease;
      this.code = code;
      return AuthResult.success("recovery");
    }
  }
}
