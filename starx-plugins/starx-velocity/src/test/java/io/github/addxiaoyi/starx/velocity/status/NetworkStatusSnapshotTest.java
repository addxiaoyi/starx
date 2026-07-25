package io.github.addxiaoyi.starx.velocity.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NetworkStatusSnapshotTest {

  @Test
  void ordersServersAndPreservesNetworkTotals() {
    NetworkStatusSnapshot snapshot = NetworkStatusSnapshot.of(
        Instant.parse("2026-07-17T04:00:00Z"),
        5,
        100,
        List.of(
            new NetworkStatusSnapshot.ServerStatus("lobby", 2, 100),
            new NetworkStatusSnapshot.ServerStatus("factions", 3, 50)));

    assertEquals(5, snapshot.onlinePlayers());
    assertEquals(100, snapshot.maxPlayers());
    assertEquals("factions", snapshot.servers().getFirst().name());
    assertEquals(3, snapshot.servers().getFirst().onlinePlayers());
  }

  @Test
  void rejectsInvalidCountsAndDuplicateServerNames() {
    assertThrows(IllegalArgumentException.class, () -> NetworkStatusSnapshot.of(
        Instant.now(), -1, 100, List.of()));
    assertThrows(IllegalArgumentException.class, () -> NetworkStatusSnapshot.of(
        Instant.now(), 1, 100, List.of(
            new NetworkStatusSnapshot.ServerStatus("lobby", 1, 100),
            new NetworkStatusSnapshot.ServerStatus("lobby", 0, 100))));
  }
}
