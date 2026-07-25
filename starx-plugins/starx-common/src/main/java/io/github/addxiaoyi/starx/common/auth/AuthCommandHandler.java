package io.github.addxiaoyi.starx.common.auth;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class AuthCommandHandler {

  private final AuthOperations auth;

  public AuthCommandHandler(AuthService authService) {
    this(new ServiceOperations(Objects.requireNonNull(authService, "authService")));
  }

  AuthCommandHandler(AuthOperations auth) {
    this.auth = Objects.requireNonNull(auth, "auth");
  }

  public AuthResult handleCredentials(
      AuthLease lease,
      UUID playerId,
      String username,
      String rawInput,
      InetAddress address,
      String deviceId
  ) {
    String credential = normalizeCredential(rawInput);
    if (credential == null) {
      return AuthResult.failure("密码不能为空");
    }
    if (credential.startsWith("/")) {
      return AuthResult.failure("请直接输入密码，不要添加斜杠命令");
    }

    return this.auth.isUserRegistered(playerId)
        ? this.auth.login(lease, playerId, username, credential, address, deviceId)
        : this.auth.register(lease, playerId, username, credential);
  }

  public AuthResult handleSecondFactor(AuthLease lease, UUID playerId, String rawInput) {
    if (rawInput == null || rawInput.isBlank()) {
      return AuthResult.failure("验证码不能为空");
    }

    String code = rawInput.trim();
    boolean recoveryCode = code.length() == 10
        && code.chars().allMatch(Character::isLetterOrDigit);
    if (recoveryCode) {
      return this.auth.verifyRecoveryCode(lease, playerId, code);
    }
    if (code.length() != 6 || !code.chars().allMatch(Character::isDigit)) {
      return AuthResult.failure("请输入 6 位验证码或 10 位恢复码");
    }
    return this.auth.verifyTotp(lease, playerId, code);
  }

  /** @deprecated Connection flows must choose the credential phase explicitly. */
  @Deprecated(forRemoval = true)
  public AuthResult handle(
      AuthLease lease,
      UUID playerId,
      String username,
      String rawInput,
      InetAddress address,
      String deviceId
  ) {
    return this.handleCredentials(lease, playerId, username, rawInput, address, deviceId);
  }

  private static String normalizeCredential(String rawInput) {
    if (rawInput == null || rawInput.isBlank()) {
      return null;
    }

    String input = rawInput.trim();
    String lower = input.toLowerCase(Locale.ROOT);
    for (String command : new String[]{"/register ", "/reg ", "/login ", "/l "}) {
      if (lower.startsWith(command)) {
        String credential = input.substring(command.length()).trim();
        return credential.isEmpty() ? null : credential;
      }
    }
    return input;
  }

  interface AuthOperations {
    boolean isUserRegistered(UUID playerId);

    AuthResult login(
        AuthLease lease,
        UUID playerId,
        String username,
        String password,
        InetAddress address,
        String deviceId
    );

    AuthResult register(AuthLease lease, UUID playerId, String username, String password);

    AuthResult verifyTotp(AuthLease lease, UUID playerId, String code);

    AuthResult verifyRecoveryCode(AuthLease lease, UUID playerId, String code);
  }

  private record ServiceOperations(AuthService service) implements AuthOperations {
    @Override
    public boolean isUserRegistered(UUID playerId) {
      return this.service.isUserRegistered(playerId);
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
      return this.service.login(lease, playerId, username, password, null, address, deviceId);
    }

    @Override
    public AuthResult register(
        AuthLease lease,
        UUID playerId,
        String username,
        String password
    ) {
      return this.service.register(lease, playerId, username, password, null);
    }

    @Override
    public AuthResult verifyTotp(AuthLease lease, UUID playerId, String code) {
      return this.service.verifyTotp(lease, playerId, code);
    }

    @Override
    public AuthResult verifyRecoveryCode(AuthLease lease, UUID playerId, String code) {
      return this.service.verifyRecoveryCode(lease, playerId, code);
    }
  }
}
