package io.github.addxiaoyi.starx.common.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerSessionLedgerTest {
  @Test
  void switchServerClosesTheOldSegmentAndKeepsTotals() {
    UUID player = UUID.randomUUID();
    PlayerSessionLedger ledger = new PlayerSessionLedger();

    ledger.connected(player, "lobby", 1_000L);
    ledger.switched(player, "survival", 4_000L);
    ledger.disconnected(player, 9_000L, DisconnectReason.NORMAL);

    assertEquals(8_000L, ledger.totalPlaytime(player));
    assertEquals(3_000L, ledger.playtime(player, "lobby"));
    assertEquals(5_000L, ledger.playtime(player, "survival"));
    assertEquals(1, ledger.loginCount(player));
  }

  @Test
  void duplicateDisconnectDoesNotDoubleCountAndRecordsAbnormalReason() {
    UUID player = UUID.randomUUID();
    PlayerSessionLedger ledger = new PlayerSessionLedger();
    ledger.connected(player, "lobby", 100L);
    ledger.disconnected(player, 500L, DisconnectReason.TIMEOUT);
    ledger.disconnected(player, 900L, DisconnectReason.KICKED);

    assertEquals(400L, ledger.totalPlaytime(player));
    assertEquals(DisconnectReason.TIMEOUT, ledger.lastDisconnect(player));
  }
}
