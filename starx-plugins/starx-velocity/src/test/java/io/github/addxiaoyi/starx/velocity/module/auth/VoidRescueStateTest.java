package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoidRescueStateTest {

  @Test
  void rescuesOnlyBelowConfiguredThreshold() {
    VoidRescueState rescue = new VoidRescueState(84.0);

    assertFalse(rescue.shouldRescue(84.0));
    assertTrue(rescue.shouldRescue(83.9));
  }

  @Test
  void unrelatedTeleportEventCannotImmediatelyClearPendingRescue() {
    VoidRescueState rescue = new VoidRescueState(84.0);

    assertTrue(rescue.shouldRescue(10.0));
    assertFalse(rescue.shouldRescue(9.0));
    assertFalse(rescue.observePosition(9.0));
    assertFalse(rescue.shouldRescue(8.0));
  }

  @Test
  void positionAtOrAboveThresholdConfirmsRescueAndAllowsAnotherRescue() {
    VoidRescueState rescue = new VoidRescueState(84.0);

    assertTrue(rescue.shouldRescue(10.0));
    assertTrue(rescue.observePosition(84.0));
    assertTrue(rescue.shouldRescue(8.0));
  }

  @Test
  void failedTeleportDoesNotLeavePendingState() {
    VoidRescueState rescue = new VoidRescueState(84.0);

    assertTrue(rescue.shouldRescue(10.0));
    rescue.cancelPending();

    assertTrue(rescue.shouldRescue(9.0));
  }
}