package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class UworldCountdownFrameTest {

  @Test
  void mapsRemainingTimeToExperienceAndUrgentSound() {
    UworldCountdownFrame normal = UworldCountdownFrame.at(300, 125);
    UworldCountdownFrame urgent = UworldCountdownFrame.at(300, 7);

    assertEquals(125, normal.level());
    assertEquals(125f / 300f, normal.progress(), 0.0001f);
    assertTrue(normal.playSound());
    assertFalse(UworldCountdownFrame.at(300, 124).playSound());
    assertTrue(urgent.playSound());
    assertTrue(urgent.pitch() > normal.pitch());
  }

  @Test
  void clampsExpiredCountdownToEmptyBar() {
    UworldCountdownFrame expired = UworldCountdownFrame.at(300, -3);

    assertEquals(0, expired.level());
    assertEquals(0f, expired.progress());
  }
}
