package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.common.auth.AuthService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;

final class AuthLoginBarrier {

  private AuthLoginBarrier() {
  }

  static <P, S> Optional<Component> enforce(
      AuthFlowIndex<P, S, Component> flows,
      P player,
      boolean finalAllowed,
      Optional<Component> externalReason,
      Component fallback
  ) {
    Objects.requireNonNull(flows, "flows");
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(externalReason, "externalReason");
    Objects.requireNonNull(fallback, "fallback");

    Optional<Component> starxReason = flows.denial(player);
    if (starxReason.isPresent()) {
      return starxReason;
    }
    if (finalAllowed) {
      return Optional.empty();
    }

    Component reason = externalReason.orElse(fallback);
    flows.deny(player, reason);
    return Optional.of(reason);
  }

  static <P, S> Optional<Component> enforceAndClose(
      AuthFlowIndex<P, S, Component> flows,
      P player,
      UUID playerId,
      boolean finalAllowed,
      Optional<Component> externalReason,
      Component fallback,
      AuthService authService
  ) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(authService, "authService");

    Optional<Component> reason = enforce(
        flows, player, finalAllowed, externalReason, fallback);
    if (reason.isEmpty()) {
      return reason;
    }

    flows.lease(player).ifPresent(lease ->
        authService.closeConnection(playerId, lease));
    return reason;
  }
}
