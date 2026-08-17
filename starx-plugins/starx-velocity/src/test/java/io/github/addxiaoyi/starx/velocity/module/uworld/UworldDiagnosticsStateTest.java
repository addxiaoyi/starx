package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class UworldDiagnosticsStateTest {

  @Test
  void retainsThePreviousServerUntilTheExactSessionFinishes() {
    Object player = new Object();
    Object previous = new Object();
    Object fallback = new Object();
    Object session = new Object();
    UworldDiagnosticsState<Object, Object, Object> state = new UworldDiagnosticsState<>();

    state.begin(player, previous);
    assertTrue(state.bind(player, session));

    assertSame(previous, state.returnTarget(player, fallback));
    assertSame(previous, state.returnTarget(player, fallback));
    assertTrue(state.finish(player, session));
    assertSame(fallback, state.returnTarget(player, fallback));
  }

  @Test
  void foreignSessionCannotOwnOrClearTheDiagnosticEntry() {
    Object player = new Object();
    Object previous = new Object();
    Object fallback = new Object();
    Object diagnosticSession = new Object();
    Object foreignSession = new Object();
    UworldDiagnosticsState<Object, Object, Object> state = new UworldDiagnosticsState<>();
    state.begin(player, previous);
    assertTrue(state.bind(player, diagnosticSession));

    assertFalse(state.owns(player, foreignSession));
    assertFalse(state.finish(player, foreignSession));
    assertSame(previous, state.returnTarget(player, fallback));

    assertTrue(state.owns(player, diagnosticSession));
    assertTrue(state.finish(player, diagnosticSession));
  }

  @Test
  void doesNotExposeAnOldConnectionEntryThroughAnEqualReplacement() {
    EqualPlayer first = new EqualPlayer("same-uuid");
    EqualPlayer replacement = new EqualPlayer("same-uuid");
    Object session = new Object();
    UworldDiagnosticsState<EqualPlayer, Object, Object> state = new UworldDiagnosticsState<>();

    state.begin(first, new Object());
    assertTrue(state.bind(first, session));

    assertFalse(state.owns(replacement, session));
    assertNull(state.returnTarget(replacement, null));
    assertTrue(state.owns(first, session));
  }

  private record EqualPlayer(String id) {
    @Override
    public boolean equals(Object other) {
      return other instanceof EqualPlayer player && this.id.equals(player.id);
    }

    @Override
    public int hashCode() {
      return this.id.hashCode();
    }
  }
}
