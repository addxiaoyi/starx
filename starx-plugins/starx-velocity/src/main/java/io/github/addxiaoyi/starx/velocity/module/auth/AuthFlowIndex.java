package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.common.auth.AuthLease;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

final class AuthFlowIndex<P, S, D> {

  enum BeginResult {
    ACCEPTED,
    DUPLICATE
  }

  enum ConnectResult {
    COMPLETED,
    WRONG_TARGET,
    IGNORED
  }

  enum InputType {
    PASSWORD,
    TOTP
  }

  enum Phase {
    LOGIN_PENDING,
    PASSWORD_PENDING,
    PASSWORD_VERIFYING,
    TOTP_PENDING,
    TOTP_VERIFYING,
    WEB_APPROVAL_PENDING,
    TARGET_PENDING,
    COMPLETE,
    DENIED
  }

  private final AuthConnectionOwner<P> owners = new AuthConnectionOwner<>();
  private final ConcurrentMap<IdentityKey<P>, Flow<S, D>> flows = new ConcurrentHashMap<>();

  synchronized BeginResult begin(UUID playerId, P player, D duplicateDenial) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(player, "player");
    IdentityKey<P> key = new IdentityKey<>(player);
    if (this.flows.containsKey(key)) {
      return BeginResult.DUPLICATE;
    }
    if (!this.owners.claim(playerId, player)) {
      this.flows.put(
          key,
          new Flow<>(
              playerId,
              null,
              false,
              new AtomicReference<>(
                  new State<>(Phase.DENIED, null, duplicateDenial))));
      return BeginResult.DUPLICATE;
    }

    this.flows.put(
        key,
        new Flow<>(
            playerId,
            AuthLease.create(),
            true,
            new AtomicReference<>(new State<>(Phase.LOGIN_PENDING, null, null))));
    return BeginResult.ACCEPTED;
  }

  boolean awaitPassword(P player) {
    return this.transition(player, Phase.LOGIN_PENDING, Phase.PASSWORD_PENDING, null);
  }

  boolean awaitTotp(P player) {
    Flow<S, D> flow = this.flow(player);
    if (flow == null) {
      return false;
    }
    while (true) {
      State<S, D> current = flow.state().get();
      if (current.phase() != Phase.PASSWORD_PENDING
          && current.phase() != Phase.PASSWORD_VERIFYING) {
        return false;
      }
      if (flow.state().compareAndSet(
          current, new State<>(Phase.TOTP_PENDING, null, current.denial()))) {
        return true;
      }
    }
  }

  boolean awaitWebApproval(P player) {
    Flow<S, D> flow = this.flow(player);
    if (flow == null) return false;
    while (true) {
      State<S, D> current = flow.state().get();
      if (current.phase() != Phase.PASSWORD_PENDING
          && current.phase() != Phase.PASSWORD_VERIFYING) {
        return false;
      }
      if (flow.state().compareAndSet(
          current, new State<>(Phase.WEB_APPROVAL_PENDING, null, current.denial()))) {
        return true;
      }
    }
  }

  Optional<InputType> claimInput(P player) {
    Flow<S, D> flow = this.flow(player);
    if (flow == null) {
      return Optional.empty();
    }
    while (true) {
      State<S, D> current = flow.state().get();
      InputType input;
      Phase verifying;
      if (current.phase() == Phase.PASSWORD_PENDING) {
        input = InputType.PASSWORD;
        verifying = Phase.PASSWORD_VERIFYING;
      } else if (current.phase() == Phase.TOTP_PENDING) {
        input = InputType.TOTP;
        verifying = Phase.TOTP_VERIFYING;
      } else {
        return Optional.empty();
      }
      if (flow.state().compareAndSet(
          current, new State<>(verifying, current.target(), current.denial()))) {
        return Optional.of(input);
      }
    }
  }

  boolean retryInput(P player, InputType input) {
    Objects.requireNonNull(input, "input");
    return input == InputType.PASSWORD
        ? this.transition(player, Phase.PASSWORD_VERIFYING, Phase.PASSWORD_PENDING, null)
        : this.transition(player, Phase.TOTP_VERIFYING, Phase.TOTP_PENDING, null);
  }

  boolean route(P player, S target) {
    Objects.requireNonNull(target, "target");
    Flow<S, D> flow = this.flow(player);
    if (flow == null) {
      return false;
    }

    while (true) {
      State<S, D> current = flow.state().get();
      if (current.phase() != Phase.LOGIN_PENDING
          && current.phase() != Phase.PASSWORD_PENDING
          && current.phase() != Phase.PASSWORD_VERIFYING
          && current.phase() != Phase.TOTP_PENDING
          && current.phase() != Phase.TOTP_VERIFYING
          && current.phase() != Phase.WEB_APPROVAL_PENDING) {
        return false;
      }
      if (flow.state().compareAndSet(
          current, new State<>(Phase.TARGET_PENDING, target, null))) {
        return true;
      }
    }
  }

  boolean requiresAuth(P player) {
    Flow<S, D> flow = this.flow(player);
    return flow != null && flow.state().get().phase() != Phase.COMPLETE;
  }

  boolean requiresInput(P player) {
    Phase phase = this.phase(player).orElse(null);
    return phase == Phase.PASSWORD_PENDING || phase == Phase.TOTP_PENDING;
  }

  boolean allowsBackend(P player, S target) {
    Flow<S, D> flow = this.flow(player);
    if (flow == null) {
      return true;
    }
    State<S, D> state = flow.state().get();
    return state.phase() == Phase.TARGET_PENDING && state.target() == target;
  }

  ConnectResult connected(P player, S target) {
    Flow<S, D> flow = this.flow(player);
    if (flow == null) {
      return ConnectResult.IGNORED;
    }

    while (true) {
      State<S, D> current = flow.state().get();
      if (current.phase() != Phase.TARGET_PENDING) {
        return ConnectResult.IGNORED;
      }

      boolean matches = current.target() == target;
      Phase terminal = matches ? Phase.COMPLETE : Phase.DENIED;
      if (flow.state().compareAndSet(
          current, new State<>(terminal, current.target(), current.denial()))) {
        return matches ? ConnectResult.COMPLETED : ConnectResult.WRONG_TARGET;
      }
    }
  }

  boolean deny(P player) {
    return this.deny(player, null);
  }

  boolean deny(P player, D reason) {
    Flow<S, D> flow = this.flow(player);
    if (flow == null) {
      return false;
    }

    while (true) {
      State<S, D> current = flow.state().get();
      if (current.phase() == Phase.COMPLETE || current.phase() == Phase.DENIED) {
        return false;
      }
      if (flow.state().compareAndSet(
          current, new State<>(Phase.DENIED, current.target(), reason))) {
        return true;
      }
    }
  }

  synchronized boolean close(UUID playerId, P player) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(player, "player");
    IdentityKey<P> key = new IdentityKey<>(player);
    Flow<S, D> flow = this.flows.get(key);
    if (flow == null) {
      return false;
    }
    if (!flow.ownsUuid()) {
      this.flows.remove(key, flow);
      return false;
    }
    if (!flow.playerId().equals(playerId) || !this.owners.isOwner(playerId, player)) {
      return false;
    }

    this.flows.remove(key, flow);
    return this.owners.release(playerId, player);
  }

  Optional<Phase> phase(P player) {
    Flow<S, D> flow = this.flow(player);
    return flow == null ? Optional.empty() : Optional.of(flow.state().get().phase());
  }

  Optional<S> target(P player) {
    Flow<S, D> flow = this.flow(player);
    return flow == null ? Optional.empty() : Optional.ofNullable(flow.state().get().target());
  }

  Optional<D> denial(P player) {
    Flow<S, D> flow = this.flow(player);
    return flow == null ? Optional.empty() : Optional.ofNullable(flow.state().get().denial());
  }

  Optional<AuthLease> lease(P player) {
    Flow<S, D> flow = this.flow(player);
    return flow == null ? Optional.empty() : Optional.ofNullable(flow.lease());
  }

  synchronized void clear() {
    this.flows.clear();
    this.owners.clear();
  }

  private boolean transition(P player, Phase expected, Phase next, S target) {
    Flow<S, D> flow = this.flow(player);
    if (flow == null) {
      return false;
    }
    State<S, D> current = flow.state().get();
    return current.phase() == expected
        && flow.state().compareAndSet(
            current, new State<>(next, target, current.denial()));
  }

  private Flow<S, D> flow(P player) {
    return this.flows.get(new IdentityKey<>(player));
  }

  private record Flow<S, D>(
      UUID playerId,
      AuthLease lease,
      boolean ownsUuid,
      AtomicReference<State<S, D>> state
  ) {
  }

  private record State<S, D>(Phase phase, S target, D denial) {
  }

  private static final class IdentityKey<P> {
    private final P value;
    private final int hash;

    private IdentityKey(P value) {
      this.value = Objects.requireNonNull(value, "player");
      this.hash = System.identityHashCode(value);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof IdentityKey<?> key && this.value == key.value;
    }

    @Override
    public int hashCode() {
      return this.hash;
    }
  }
}
