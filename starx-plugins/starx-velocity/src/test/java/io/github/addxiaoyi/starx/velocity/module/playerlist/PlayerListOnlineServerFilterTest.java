package io.github.addxiaoyi.starx.velocity.module.playerlist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerListOnlineServerFilterTest {

  @Test
  void hidesEmptyAndUnknownServerCounts() {
    assertFalse(PlayerListModule.shouldDisplayOnlineServer(0));
    assertFalse(PlayerListModule.shouldDisplayOnlineServer(-1));
  }

  @Test
  void keepsServersWithRealOnlinePlayers() {
    assertTrue(PlayerListModule.shouldDisplayOnlineServer(1));
    assertTrue(PlayerListModule.shouldDisplayOnlineServer(100));
  }
}
