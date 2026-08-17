package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.uworld.UworldCreationException;
import org.junit.jupiter.api.Test;

final class UworldRegistryTest {

  @Test
  void keepsAuthenticationAndDiagnosticsWorldsIndependent() {
    UworldRegistry<Object, Object, Object> registry = new UworldRegistry<>();

    registry.registerWorld("starx.auth", "auth", new Object());
    registry.registerWorld("starx.diagnostics", "diagnostics", new Object());

    assertEquals(2, registry.worldCount());
  }

  @Test
  void duplicateWorldReportsBothOwners() {
    UworldRegistry<Object, Object, Object> registry = new UworldRegistry<>();
    registry.registerWorld("starx.auth", "shared", new Object());

    UworldCreationException error = assertThrows(UworldCreationException.class,
        () -> registry.registerWorld("starx.diagnostics", "shared", new Object()));

    assertTrue(error.getMessage().contains("starx.auth"));
    assertTrue(error.getMessage().contains("starx.diagnostics"));
  }

  @Test
  void onePlayerOwnsOnlyOneExactSession() {
    UworldRegistry<Object, Object, Object> registry = new UworldRegistry<>();
    Object player = new Object();
    Object first = new Object();
    Object second = new Object();

    assertEquals(UworldRegistry.ClaimResult.ACCEPTED, registry.claim(player, first));
    assertEquals(UworldRegistry.ClaimResult.PLAYER_BUSY, registry.claim(player, second));
    assertFalse(registry.release(player, second));
    assertTrue(registry.release(player, first));
  }

  @Test
  void stoppingRejectsNewSessions() {
    UworldRegistry<Object, Object, Object> registry = new UworldRegistry<>();
    registry.beginStopping();

    assertEquals(UworldRegistry.ClaimResult.RUNTIME_STOPPING,
        registry.claim(new Object(), new Object()));
  }

  @Test
  void doesNotExposeAnOldConnectionSessionThroughAnEqualReplacement() {
    UworldRegistry<EqualPlayer, Object, Object> registry = new UworldRegistry<>();
    EqualPlayer first = new EqualPlayer("same-uuid");
    EqualPlayer replacement = new EqualPlayer("same-uuid");
    Object session = new Object();

    assertEquals(UworldRegistry.ClaimResult.ACCEPTED, registry.claim(first, session));
    assertTrue(registry.session(replacement).isEmpty());
    assertSame(session, registry.session(first).orElseThrow());
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
