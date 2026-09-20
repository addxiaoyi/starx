package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.auth.AuthLease;
import io.github.addxiaoyi.starx.common.auth.CrossDeviceApprovalService;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

public final class CrossDeviceActionExecutor
    implements CrossDeviceApprovalHandler.ApprovalExecutor {
  private final BiFunction<UUID, String, Boolean> emailBinder;
  private final BiFunction<UUID, String, Boolean> skinRefresher;
  private final BiFunction<UUID, AuthLease, Boolean> loginApprover;

  public CrossDeviceActionExecutor(
      BiFunction<UUID, String, Boolean> emailBinder,
      BiFunction<UUID, String, Boolean> skinRefresher) {
    this(emailBinder, skinRefresher, (uuid, lease) -> false);
  }

  public CrossDeviceActionExecutor(
      BiFunction<UUID, String, Boolean> emailBinder,
      BiFunction<UUID, String, Boolean> skinRefresher,
      BiFunction<UUID, AuthLease, Boolean> loginApprover) {
    this.emailBinder = Objects.requireNonNull(emailBinder, "emailBinder");
    this.skinRefresher = Objects.requireNonNull(skinRefresher, "skinRefresher");
    this.loginApprover = Objects.requireNonNull(loginApprover, "loginApprover");
  }

  public boolean execute(CrossDeviceApprovalService.Challenge challenge, String email) {
    Objects.requireNonNull(challenge, "challenge");
    return execute(challenge.token(), challenge, email);
  }

  @Override
  public boolean execute(
      String operationId, CrossDeviceApprovalService.Challenge challenge, String email) {
    if (Objects.requireNonNull(operationId, "operationId").isBlank()) {
      throw new IllegalArgumentException("operationId must not be blank");
    }
    Objects.requireNonNull(challenge, "challenge");
    return switch (challenge.action()) {
      case BIND_EMAIL -> email != null && !email.isBlank()
          && Boolean.TRUE.equals(this.emailBinder.apply(challenge.playerId(), email.trim()));
      case BIND_SKIN_ACCOUNT -> Boolean.TRUE.equals(
          this.skinRefresher.apply(challenge.playerId(), challenge.username()));
      // TOTP remains opt-in and needs a verified six-digit code before persistence.
      case ENABLE_TOTP -> false;
      case APPROVE_LOGIN -> challenge.authLease() != null
          && Boolean.TRUE.equals(this.loginApprover.apply(
              challenge.playerId(), challenge.authLease()));
    };
  }
}
