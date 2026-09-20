package io.github.addxiaoyi.starx.limbo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class KeepAliveResponseGuardTest {

  @Test
  void ignoresExactlyOneLateResponseFromThePreviousServer() {
    KeepAliveResponseGuard guard = new KeepAliveResponseGuard();

    assertEquals(
        KeepAliveResponseGuard.Decision.IGNORE_STALE,
        guard.classify(false, 0L, 41L));
    assertEquals(
        KeepAliveResponseGuard.Decision.REJECT,
        guard.classify(false, 0L, 42L));
  }

  @Test
  void acceptsTheExpectedResponseAfterIgnoringTheTransitionRace() {
    KeepAliveResponseGuard guard = new KeepAliveResponseGuard();

    assertEquals(
        KeepAliveResponseGuard.Decision.IGNORE_STALE,
        guard.classify(true, 100L, 99L));
    assertEquals(
        KeepAliveResponseGuard.Decision.ACCEPT,
        guard.classify(true, 100L, 100L));
  }

  @Test
  void expectedResponseClosesTheTransitionGraceWindow() {
    KeepAliveResponseGuard guard = new KeepAliveResponseGuard();

    assertEquals(
        KeepAliveResponseGuard.Decision.ACCEPT,
        guard.classify(true, 7L, 7L));
    assertEquals(
        KeepAliveResponseGuard.Decision.REJECT,
        guard.classify(false, 7L, 8L));
  }
}
