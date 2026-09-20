package io.github.addxiaoyi.starx.velocity.module.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PlayerLatencyTrackerTest {
  private static final UUID PLAYER = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

  @Test
  void rejectsInvalidSamplesAndStartsUnknown() {
    PlayerLatencyTracker tracker = new PlayerLatencyTracker();
    assertEquals(-1, tracker.snapshot(PLAYER).smoothedPing());
    assertEquals(-1, tracker.observe(PLAYER, -1).smoothedPing());
    assertEquals(-1, tracker.observe(PLAYER, 10_001).smoothedPing());
    assertEquals(0, tracker.size());
  }

  @Test
  void usesMedianAndSmoothsSpikes() {
    PlayerLatencyTracker tracker = new PlayerLatencyTracker();
    tracker.observe(PLAYER, 100);
    tracker.observe(PLAYER, 100);
    tracker.observe(PLAYER, 100);
    tracker.observe(PLAYER, 100);
    assertEquals(100, tracker.observe(PLAYER, 900).smoothedPing());
    assertEquals(900, tracker.snapshot(PLAYER).rawPing());
  }

  @Test
  void removesDisconnectedPlayers() {
    PlayerLatencyTracker tracker = new PlayerLatencyTracker();
    tracker.observe(PLAYER, 40);
    tracker.remove(PLAYER);
    assertEquals(0, tracker.size());
    assertEquals(-1, tracker.snapshot(PLAYER).smoothedPing());
  }

  @Test
  void calculatesP95FromSmoothedOnlineSamples() {
    PlayerLatencyTracker tracker = new PlayerLatencyTracker();
    tracker.observe(PLAYER, 40);
    tracker.observe(UUID.fromString("123e4567-e89b-42d3-a456-426614174001"), 120);
    assertEquals(120, tracker.percentile(95));
  }

  @Test
  void expiresSnapshotsWhenSamplingStops() {
    AtomicLong clock = new AtomicLong(1L);
    PlayerLatencyTracker tracker = new PlayerLatencyTracker(clock::get);
    tracker.observe(PLAYER, 40);
    assertEquals(40, tracker.snapshot(PLAYER).smoothedPing());
    clock.set(2_000_000_002L);
    assertEquals(-1, tracker.snapshot(PLAYER).smoothedPing());
  }
}
