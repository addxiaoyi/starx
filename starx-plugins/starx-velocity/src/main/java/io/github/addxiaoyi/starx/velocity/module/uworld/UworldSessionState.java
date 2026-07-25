package io.github.addxiaoyi.starx.velocity.module.uworld;

import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldPhase;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

final class UworldSessionState<T> {

  enum TargetConnectResult {
    COMPLETED,
    WRONG_TARGET,
    IGNORED
  }

  private final AtomicReference<State<T>> state = new AtomicReference<>(
      new State<>(UworldPhase.ENTERING, null, null));
  private final BiPredicate<T, T> targetMatches;

  UworldSessionState() {
    this((expected, actual) -> expected == actual);
  }

  UworldSessionState(BiPredicate<T, T> targetMatches) {
    this.targetMatches = Objects.requireNonNull(targetMatches, "targetMatches");
  }

  boolean activate() {
    State<T> current = this.state.get();
    return current.phase() == UworldPhase.ENTERING
        && this.state.compareAndSet(current, new State<>(UworldPhase.ACTIVE, null, null));
  }

  boolean beginTransfer(T target) {
    Objects.requireNonNull(target, "target");
    State<T> current = this.state.get();
    return current.phase() == UworldPhase.ACTIVE
        && this.state.compareAndSet(
            current,
            new State<>(UworldPhase.TRANSFERRING, target, null));
  }

  TargetConnectResult onConnected(T actual) {
    while (true) {
      State<T> current = this.state.get();
      if (current.phase() != UworldPhase.TRANSFERRING) {
        return TargetConnectResult.IGNORED;
      }

      boolean matches = this.targetMatches.test(current.target(), actual);
      UworldOutcomeType outcome = matches
          ? UworldOutcomeType.TRANSFERRED
          : UworldOutcomeType.WRONG_TARGET;
      State<T> closed = new State<>(UworldPhase.CLOSED, current.target(), outcome);
      if (this.state.compareAndSet(current, closed)) {
        return matches ? TargetConnectResult.COMPLETED : TargetConnectResult.WRONG_TARGET;
      }
    }
  }

  boolean close(UworldOutcomeType outcome) {
    Objects.requireNonNull(outcome, "outcome");
    while (true) {
      State<T> current = this.state.get();
      if (current.phase() == UworldPhase.CLOSED) {
        return false;
      }
      if (this.state.compareAndSet(
          current,
          new State<>(UworldPhase.CLOSED, current.target(), outcome))) {
        return true;
      }
    }
  }

  boolean close(UworldPhase expectedPhase, UworldOutcomeType outcome) {
    Objects.requireNonNull(expectedPhase, "expectedPhase");
    Objects.requireNonNull(outcome, "outcome");
    while (true) {
      State<T> current = this.state.get();
      if (current.phase() != expectedPhase || current.phase() == UworldPhase.CLOSED) {
        return false;
      }
      if (this.state.compareAndSet(
          current,
          new State<>(UworldPhase.CLOSED, current.target(), outcome))) {
        return true;
      }
    }
  }

  boolean allowsTarget(T target) {
    State<T> current = this.state.get();
    return current.phase() == UworldPhase.TRANSFERRING
        && this.targetMatches.test(current.target(), target);
  }

  UworldPhase phase() {
    return this.state.get().phase();
  }

  UworldOutcomeType outcome() {
    return this.state.get().outcome();
  }

  T target() {
    return this.state.get().target();
  }

  private record State<T>(UworldPhase phase, T target, UworldOutcomeType outcome) {
  }
}
