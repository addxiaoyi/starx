package io.github.addxiaoyi.starx.velocity.module.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayerListNetworkSnapshotTest {

  @Test
  void excludesEmptyBackendsFromOnlineServerLabels() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("empty", 0);
    counts.put("lobby", 2);
    counts.put("survival", 1);

    assertEquals(
        "lobby 2 <dark_gray>|</dark_gray> survival 1",
        PlayerListModule.occupiedServerLabels(counts, name -> name));
  }

  @Test
  void reportsNoOccupiedBackendWhenAllServersAreEmpty() {
    assertEquals(
        "暂无在线子服",
        PlayerListModule.occupiedServerLabels(Map.of("lobby", 0, "survival", 0), name -> name));
  }
}
