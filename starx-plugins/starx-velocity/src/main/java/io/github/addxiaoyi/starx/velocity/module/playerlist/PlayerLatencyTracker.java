package io.github.addxiaoyi.starx.velocity.module.playerlist;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Samples Velocity's player ping and exposes a bounded, smoothed snapshot. */
public final class PlayerLatencyTracker {
  public static final int UNKNOWN_PING = -1;
  private static final int MAX_VALID_PING = 10_000;
  private static final int WINDOW_SIZE = 5;
  private static final double EMA_ALPHA = 0.5d;
  private static final long STALE_AFTER_NANOS = 2_000_000_000L;

  private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();
  private final LongSupplier clock;

  public PlayerLatencyTracker() {
    this(System::nanoTime);
  }

  PlayerLatencyTracker(LongSupplier clock) {
    this.clock = clock;
  }

  public Snapshot observe(UUID playerId, long rawPing) {
    if (playerId == null || rawPing < 0 || rawPing > MAX_VALID_PING) {
      return playerId == null ? Snapshot.unknown() : snapshot(playerId);
    }
    Sample sample = this.samples.computeIfAbsent(playerId, ignored -> new Sample());
    synchronized (sample) {
      sample.values.addLast((int) rawPing);
      while (sample.values.size() > WINDOW_SIZE) sample.values.removeFirst();
      int median = median(sample.values);
      sample.smoothed = sample.smoothed < 0
          ? median
          : (int) Math.round(sample.smoothed + EMA_ALPHA * (median - sample.smoothed));
      sample.raw = (int) rawPing;
      sample.updatedAt = this.clock.getAsLong();
      return sample.snapshot();
    }
  }

  public Snapshot snapshot(UUID playerId) {
    Sample sample = playerId == null ? null : this.samples.get(playerId);
    if (sample == null) return Snapshot.unknown();
    synchronized (sample) {
      if (sample.updatedAt == 0L
          || this.clock.getAsLong() - sample.updatedAt > STALE_AFTER_NANOS) {
        return Snapshot.unknown();
      }
      return sample.snapshot();
    }
  }

  public void remove(UUID playerId) {
    if (playerId != null) this.samples.remove(playerId);
  }

  public int size() {
    return this.samples.size();
  }

  /** Returns the percentile across current smoothed player samples, or unknown when empty. */
  public int percentile(int percentile) {
    if (percentile < 1 || percentile > 100) {
      throw new IllegalArgumentException("percentile must be between 1 and 100");
    }
    long now = this.clock.getAsLong();
    int[] values = this.samples.values().stream().mapToInt(sample -> {
      synchronized (sample) {
        return sample.updatedAt != 0L && now - sample.updatedAt <= STALE_AFTER_NANOS
            ? sample.smoothed : UNKNOWN_PING;
      }
    }).filter(value -> value >= 0).sorted().toArray();
    if (values.length == 0) return UNKNOWN_PING;
    int index = (int) Math.ceil(percentile / 100.0d * values.length) - 1;
    return values[Math.max(0, Math.min(index, values.length - 1))];
  }

  public void retain(Set<UUID> onlinePlayers) {
    if (onlinePlayers == null) {
      this.samples.clear();
      return;
    }
    this.samples.keySet().removeIf(playerId -> !onlinePlayers.contains(playerId));
  }

  private static int median(Deque<Integer> values) {
    int[] sorted = values.stream().mapToInt(Integer::intValue).toArray();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  public record Snapshot(int rawPing, int smoothedPing, long updatedAtNanos) {
    static Snapshot unknown() {
      return new Snapshot(UNKNOWN_PING, UNKNOWN_PING, 0L);
    }
  }

  private static final class Sample {
    private final Deque<Integer> values = new ArrayDeque<>(WINDOW_SIZE);
    private int raw = UNKNOWN_PING;
    private int smoothed = UNKNOWN_PING;
    private long updatedAt;

    private Snapshot snapshot() {
      return new Snapshot(this.raw, this.smoothed, this.updatedAt);
    }
  }
}
