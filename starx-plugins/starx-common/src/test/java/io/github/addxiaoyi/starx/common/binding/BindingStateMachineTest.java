package io.github.addxiaoyi.starx.common.binding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BindingStateMachineTest {
  @Test
  void followsCreatedSentConfirmedConsumedLifecycle() {
    BindingStateMachine flow = new BindingStateMachine();
    assertEquals(BindingState.SENT, flow.move(BindingState.CREATED, BindingAction.SEND));
    assertEquals(BindingState.CONFIRMED, flow.move(BindingState.SENT, BindingAction.CONFIRM));
    assertEquals(BindingState.CONSUMED, flow.move(BindingState.CONFIRMED, BindingAction.CONSUME));
  }

  @Test
  void rejectsReplayAndInvalidTransitions() {
    BindingStateMachine flow = new BindingStateMachine();
    assertThrows(IllegalStateException.class,
        () -> flow.move(BindingState.CONSUMED, BindingAction.CONSUME));
    assertThrows(IllegalStateException.class,
        () -> flow.move(BindingState.EXPIRED, BindingAction.CONFIRM));
    assertEquals(BindingState.REVOKED,
        flow.move(BindingState.CONFIRMED, BindingAction.REVOKE));
    assertEquals(BindingState.SENT,
        flow.move(BindingState.CONFIRMED, BindingAction.RELEASE));
  }
}
