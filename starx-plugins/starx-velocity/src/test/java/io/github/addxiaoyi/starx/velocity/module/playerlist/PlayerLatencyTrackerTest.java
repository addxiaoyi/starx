package io.github.addxiaoyi.starx.velocity.module.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
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

  @Test
  void survivesConcurrentSamplingAcrossAThousandPlayers() throws Exception {
    PlayerLatencyTracker tracker = new PlayerLatencyTracker(() -> 1L);
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      List<Callable<Void>> workers = IntStream.range(0, 16)
          .mapToObj(worker -> (Callable<Void>) () -> {
            for (int sample = 0; sample < 100_000; sample++) {
              UUID playerId = new UUID(0L, sample % 1_000L + 1L);
              tracker.observe(playerId, 20L + (sample + worker) % 380L);
            }
            return null;
          })
          .toList();
      for (Future<Void> future : pool.invokeAll(workers, 30, TimeUnit.SECONDS)) {
        future.get();
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1_000, tracker.size());
    int p95 = tracker.percentile(95);
    assertTrue(p95 >= 20 && p95 <= 399, "p95=" + p95);
  }

  @Test
  void sharedConsumersReadPingOnlyOncePerInterval() {
    AtomicLong clock = new AtomicLong();
    AtomicInteger reads = new AtomicInteger();
    PlayerLatencyTracker tracker = new PlayerLatencyTracker(clock::get);
    for (int attempt = 0; attempt < 100; attempt++) {
      assertEquals(40, tracker.sampleIfDue(PLAYER, () -> {
        reads.incrementAndGet();
        return 40;
      }).smoothedPing());
    }
    assertEquals(1, reads.get());
    assertEquals(40, tracker.snapshot(PLAYER).smoothedPing());
    clock.set(249_999_999L);
    assertEquals(40, tracker.sampleIfDue(PLAYER, () -> 90).rawPing());
    clock.set(250_000_000L);
    assertEquals(90, tracker.sampleIfDue(PLAYER, () -> 90).rawPing());
  }

  @Test
  void rejectsAndThrottlesInvalidAttemptsWithoutRefreshingTheirAge() {
    AtomicLong clock = new AtomicLong();
    AtomicInteger reads = new AtomicInteger();
    PlayerLatencyTracker tracker = new PlayerLatencyTracker(clock::get);
    for (int attempt = 0; attempt < 100; attempt++) {
      assertEquals(-1, tracker.sampleIfDue(PLAYER, () -> {
        reads.incrementAndGet();
        return 10_001;
      }).smoothedPing());
    }
    assertEquals(1, reads.get());
    assertEquals(0, tracker.size());
    clock.set(250_000_000L);
    assertEquals(0, tracker.sampleIfDue(PLAYER, () -> 0).smoothedPing());
    clock.set(500_000_000L);
    assertEquals(0, tracker.sampleIfDue(PLAYER, () -> -1).smoothedPing());
    clock.set(2_250_000_001L);
    assertEquals(-1, tracker.sampleIfDue(PLAYER, () -> -1).smoothedPing());
    assertEquals(0, tracker.size());
    assertEquals(-1, tracker.percentile(95));
  }

  @Test
  void resumesWithFreshWindowAfterSamplingGap() {
    AtomicLong clock = new AtomicLong();
    PlayerLatencyTracker tracker = new PlayerLatencyTracker(clock::get);
    for (int sample = 0; sample < 5; sample++) tracker.observe(PLAYER, 900);
    clock.set(2_000_000_001L);
    assertEquals(30, tracker.sampleIfDue(PLAYER, () -> 30).smoothedPing());
  }

  @Test
  void crossServerOnlyPollingDoesNotWaitForSnapshotExpiry() {
    AtomicLong clock = new AtomicLong();
    PlayerLatencyTracker tracker = new PlayerLatencyTracker(clock::get);
    for (int second = 0; second < 10; second++) {
      clock.set(second * 1_000_000_000L);
      int ping = 40 + second;
      assertEquals(ping, tracker.sampleIfDue(PLAYER, () -> ping).rawPing());
    }
    tracker.remove(PLAYER);
    assertEquals(-1, tracker.snapshot(PLAYER).smoothedPing());
    assertEquals(200, tracker.sampleIfDue(PLAYER, () -> 200).smoothedPing());
  }

  @Test
  void concurrentConsumersShareOneRead() throws Exception {
    PlayerLatencyTracker tracker = new PlayerLatencyTracker(() -> 0L);
    AtomicInteger reads = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      List<Callable<Integer>> consumers = IntStream.range(0, 1_000)
          .mapToObj(ignored -> (Callable<Integer>) () -> tracker.sampleIfDue(PLAYER, () -> {
            reads.incrementAndGet();
            return 60;
          }).smoothedPing())
          .toList();
      for (Future<Integer> future : pool.invokeAll(consumers, 10, TimeUnit.SECONDS)) {
        assertEquals(60, future.get().intValue());
      }
      assertEquals(1, reads.get());
    } finally {
      pool.shutdownNow();
    }
  }
}
