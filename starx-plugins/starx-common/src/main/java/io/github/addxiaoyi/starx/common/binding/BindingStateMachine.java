package io.github.addxiaoyi.starx.common.binding;

public final class BindingStateMachine {
  public BindingState move(BindingState current, BindingAction action) {
    if (current == null || action == null) throw new NullPointerException("state and action are required");
    BindingState next = switch (action) {
      case SEND -> current == BindingState.CREATED ? BindingState.SENT : null;
      case CONFIRM -> current == BindingState.SENT ? BindingState.CONFIRMED : null;
      case RELEASE -> current == BindingState.CONFIRMED ? BindingState.SENT : null;
      case CONSUME -> current == BindingState.CONFIRMED ? BindingState.CONSUMED : null;
      case EXPIRE -> current == BindingState.CREATED || current == BindingState.SENT ? BindingState.EXPIRED : null;
      case REVOKE -> current == BindingState.CREATED || current == BindingState.SENT || current == BindingState.CONFIRMED ? BindingState.REVOKED : null;
    };
    if (next == null) throw new IllegalStateException("Invalid binding transition: " + current + " -> " + action);
    return next;
  }
}
