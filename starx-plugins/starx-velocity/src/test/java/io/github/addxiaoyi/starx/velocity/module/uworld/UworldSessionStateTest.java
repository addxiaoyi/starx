package io.github.addxiaoyi.starx.velocity.module.uworld;

import static io.github.addxiaoyi.starx.velocity.module.uworld.UworldSessionState.TargetConnectResult.COMPLETED;
import static io.github.addxiaoyi.starx.velocity.module.uworld.UworldSessionState.TargetConnectResult.WRONG_TARGET;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldPhase;
import org.junit.jupiter.api.Test;

final class UworldSessionStateTest {

  @Test
  void transferKeepsSessionUntilExactTargetConnects() {
    UworldSessionState<String> state = new UworldSessionState<>();

    assertTrue(state.activate());
    assertTrue(state.beginTransfer("lobby"));
    assertEquals(COMPLETED, state.onConnected("lobby"));
    assertEquals(UworldPhase.CLOSED, state.phase());
    assertEquals(UworldOutcomeType.TRANSFERRED, state.outcome());
  }

  @Test
  void wrongTargetTerminatesTheSession() {
    UworldSessionState<String> state = new UworldSessionState<>();

    assertTrue(state.activate());
    assertTrue(state.beginTransfer("lobby"));
    assertEquals(WRONG_TARGET, state.onConnected("other"));
    assertEquals(UworldPhase.CLOSED, state.phase());
    assertEquals(UworldOutcomeType.WRONG_TARGET, state.outcome());
  }

  @Test
  void onlyOneTerminalOutcomeWins() {
    UworldSessionState<String> state = new UworldSessionState<>();

    assertTrue(state.close(UworldOutcomeType.TIMED_OUT));
    assertFalse(state.close(UworldOutcomeType.DISCONNECTED));
    assertEquals(UworldOutcomeType.TIMED_OUT, state.outcome());
  }

  @Test
  void phaseBoundCloseCannotTerminateALaterPhase() {
    UworldSessionState<String> state = new UworldSessionState<>();
    assertTrue(state.activate());
    java.lang.reflect.Method closeInPhase = assertDoesNotThrow(
        () -> UworldSessionState.class.getDeclaredMethod(
            "close", UworldPhase.class, UworldOutcomeType.class));

    boolean closed = assertDoesNotThrow(() -> (boolean) closeInPhase.invoke(
        state, UworldPhase.ENTERING, UworldOutcomeType.TIMED_OUT));

    assertFalse(closed);
    assertEquals(UworldPhase.ACTIVE, state.phase());
    assertTrue(assertDoesNotThrow(() -> (boolean) closeInPhase.invoke(
        state, UworldPhase.ACTIVE, UworldOutcomeType.TIMED_OUT)));
  }
}
