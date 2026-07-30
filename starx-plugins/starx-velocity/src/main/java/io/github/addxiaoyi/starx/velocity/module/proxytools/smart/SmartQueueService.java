package io.github.addxiaoyi.starx.velocity.module.proxytools.smart;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SmartQueueService {
  private static final long MILLIS_PER_MINUTE = 60_000L;
  private static final Comparator<SmartQueueEntry> PRIORITY =
      Comparator.comparingLong(SmartQueueEntry::score).reversed()
          .thenComparingLong(SmartQueueEntry::enqueueTimeMs);

  private final Map<String, Bucket> queues = new ConcurrentHashMap<>();
  private final Map<UUID, Long> joinTimestamps = new ConcurrentHashMap<>();
  private final AtomicBoolean dispatching = new AtomicBoolean();

  public void enqueue(RegisteredServer server, Player player, int baseScore) {
    String serverName = server.getServerInfo().getName();
    long now = System.currentTimeMillis();
    long score = baseScore + computeActivityScore(player, now);
    queues.computeIfAbsent(serverName, ignored -> new Bucket())
        .add(new SmartQueueEntry(player, score, now));
  }

  public Player dequeue(RegisteredServer server) {
    Bucket queue = queues.get(server.getServerInfo().getName());
    SmartQueueEntry entry = queue == null ? null : queue.poll();
    return entry == null ? null : entry.player();
  }

  public int size(RegisteredServer server) {
    Bucket queue = queues.get(server.getServerInfo().getName());
    return queue == null ? 0 : queue.size();
  }

  public Map<String, Integer> snapshot() {
    Map<String, Integer> sizes = new java.util.TreeMap<>();
    queues.forEach((server, bucket) -> sizes.put(server, bucket.size()));
    return Map.copyOf(sizes);
  }

  public void clear() {
    queues.clear();
    joinTimestamps.clear();
  }

  public int position(RegisteredServer server, Player player) {
    Bucket queue = queues.get(server.getServerInfo().getName());
    return queue == null ? 0 : queue.position(player.getUniqueId());
  }

  public long estimateWaitSeconds(
      RegisteredServer server, Player player, int releasesPerCycle, long cycleMillis) {
    if (releasesPerCycle < 1) throw new IllegalArgumentException("releasesPerCycle must be positive");
    if (cycleMillis < 1) throw new IllegalArgumentException("cycleMillis must be positive");
    int position = position(server, player);
    if (position == 0) return 0L;
    long cycles = (position + (long) releasesPerCycle - 1L) / releasesPerCycle;
    return (cycles * cycleMillis + 999L) / 1_000L;
  }

  public boolean removeFromQueue(RegisteredServer server, Player player) {
    Bucket queue = queues.get(server.getServerInfo().getName());
    return queue != null && queue.remove(player.getUniqueId());
  }

  public int removeFromAllQueues(Player player) {
    int removed = 0;
    for (Bucket queue : queues.values()) {
      if (queue.remove(player.getUniqueId())) removed++;
    }
    return removed;
  }

  public void recordJoin(Player player) {
    joinTimestamps.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
  }

  public void recordQuit(Player player) {
    joinTimestamps.remove(player.getUniqueId());
    removeFromAllQueues(player);
  }

  public int processQueues(PlayerConnector connector, int maxRelease) {
    Objects.requireNonNull(connector, "connector");
    if (maxRelease < 0) throw new IllegalArgumentException("maxRelease must not be negative");
    if (maxRelease == 0 || !this.dispatching.compareAndSet(false, true)) return 0;
    int dispatched = 0;
    try {
      int remaining = Math.max(0, maxRelease - this.inFlightCount());
      if (remaining == 0) return 0;
      for (Map.Entry<String, Bucket> queue : queues.entrySet()) {
        if (remaining == 0) break;
        List<SmartQueueEntry> claims = queue.getValue().claim(remaining);
        remaining -= claims.size();
        for (SmartQueueEntry entry : claims) {
          UUID playerId = entry.player().getUniqueId();
          CompletionStage<Boolean> result;
          try {
            result = connector.connect(entry.player(), queue.getKey());
          } catch (RuntimeException error) {
            queue.getValue().complete(playerId, false);
            continue;
          }
          if (result == null) {
            queue.getValue().complete(playerId, false);
            continue;
          }
          dispatched++;
          result.whenComplete((success, error) -> queue.getValue().complete(
              playerId, error == null && Boolean.TRUE.equals(success)));
        }
      }
    } finally {
      this.dispatching.set(false);
    }
    return dispatched;
  }

  private int inFlightCount() {
    int count = 0;
    for (Bucket queue : queues.values()) count += queue.inFlightCount();
    return count;
  }

  private int computeActivityScore(Player player, long now) {
    Long joinedAt = joinTimestamps.get(player.getUniqueId());
    if (joinedAt == null) return 0;
    return (int) Math.min(100L, Math.max(0L, now - joinedAt) / MILLIS_PER_MINUTE);
  }

  public record SmartQueueEntry(Player player, long score, long enqueueTimeMs) { }

  @FunctionalInterface
  public interface PlayerConnector {
    CompletionStage<Boolean> connect(Player player, String serverName);
  }

  private static final class Bucket {
    private final PriorityQueue<SmartQueueEntry> ordered = new PriorityQueue<>(PRIORITY);
    private final Map<UUID, SmartQueueEntry> byPlayer = new HashMap<>();
    private final Set<UUID> inFlight = new HashSet<>();

    synchronized void add(SmartQueueEntry entry) {
      UUID playerId = entry.player().getUniqueId();
      if (byPlayer.containsKey(playerId)) return;
      byPlayer.put(playerId, entry);
      ordered.add(entry);
    }

    synchronized SmartQueueEntry poll() {
      SmartQueueEntry entry = ordered.poll();
      if (entry != null) {
        UUID playerId = entry.player().getUniqueId();
        byPlayer.remove(playerId);
        inFlight.remove(playerId);
      }
      return entry;
    }

    synchronized List<SmartQueueEntry> claim(int limit) {
      if (limit <= 0) return List.of();
      List<SmartQueueEntry> snapshot = new ArrayList<>(ordered);
      snapshot.sort(PRIORITY);
      List<SmartQueueEntry> claimed = new ArrayList<>(Math.min(limit, snapshot.size()));
      for (SmartQueueEntry entry : snapshot) {
        UUID playerId = entry.player().getUniqueId();
        if (!entry.player().isActive()) {
          removeInternal(playerId);
          continue;
        }
        if (inFlight.add(playerId)) {
          claimed.add(entry);
          if (claimed.size() == limit) break;
        }
      }
      return List.copyOf(claimed);
    }

    synchronized void complete(UUID playerId, boolean success) {
      if (!inFlight.remove(playerId)) return;
      if (success) removeInternal(playerId);
    }

    synchronized boolean remove(UUID playerId) {
      inFlight.remove(playerId);
      return removeInternal(playerId);
    }

    synchronized int size() {
      return byPlayer.size();
    }

    synchronized int inFlightCount() {
      return inFlight.size();
    }

    synchronized int position(UUID playerId) {
      List<SmartQueueEntry> snapshot = new ArrayList<>(ordered);
      snapshot.sort(PRIORITY);
      for (int index = 0; index < snapshot.size(); index++) {
        if (snapshot.get(index).player().getUniqueId().equals(playerId)) return index + 1;
      }
      return 0;
    }

    private boolean removeInternal(UUID playerId) {
      SmartQueueEntry entry = byPlayer.remove(playerId);
      return entry != null && ordered.remove(entry);
    }
  }
}
