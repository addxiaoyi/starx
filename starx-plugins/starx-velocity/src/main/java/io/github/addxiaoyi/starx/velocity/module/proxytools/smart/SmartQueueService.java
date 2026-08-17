package io.github.addxiaoyi.starx.velocity.module.proxytools.smart;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
  private final Map<UUID, JoinRecord> joinRecords = new ConcurrentHashMap<>();
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
    joinRecords.clear();
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
    return queue != null && queue.remove(player);
  }

  public int removeFromAllQueues(Player player) {
    int removed = 0;
    for (Bucket queue : queues.values()) {
      if (queue.remove(player)) removed++;
    }
    return removed;
  }

  public void recordJoin(Player player) {
    joinRecords.compute(player.getUniqueId(), (ignored, current) -> {
      if (current != null && current.player() == player) return current;
      return new JoinRecord(player, System.currentTimeMillis());
    });
  }

  public void recordQuit(Player player) {
    // A late disconnect must not erase the replacement connection's activity clock.
    JoinRecord current = this.joinRecords.get(player.getUniqueId());
    if (current != null && current.player() == player) {
      this.joinRecords.remove(player.getUniqueId(), current);
    }
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
          CompletionStage<Boolean> result;
          try {
            result = connector.connect(entry.player(), queue.getKey());
          } catch (RuntimeException error) {
            queue.getValue().complete(entry.player(), false);
            continue;
          }
          if (result == null) {
            queue.getValue().complete(entry.player(), false);
            continue;
          }
          dispatched++;
          result.whenComplete((success, error) -> queue.getValue().complete(
              entry.player(), error == null && Boolean.TRUE.equals(success)));
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
    JoinRecord joined = joinRecords.get(player.getUniqueId());
    if (joined == null || joined.player() != player) return 0;
    return (int) Math.min(100L, Math.max(0L, now - joined.joinedAtMs()) / MILLIS_PER_MINUTE);
  }

  public record SmartQueueEntry(Player player, long score, long enqueueTimeMs) { }

  private record JoinRecord(Player player, long joinedAtMs) { }

  @FunctionalInterface
  public interface PlayerConnector {
    CompletionStage<Boolean> connect(Player player, String serverName);
  }

  private static final class Bucket {
    private final PriorityQueue<SmartQueueEntry> ordered = new PriorityQueue<>(PRIORITY);
    private final Map<UUID, SmartQueueEntry> byPlayer = new HashMap<>();
    private final Map<UUID, Player> inFlight = new HashMap<>();

    synchronized void add(SmartQueueEntry entry) {
      UUID playerId = entry.player().getUniqueId();
      SmartQueueEntry current = byPlayer.get(playerId);
      if (current != null && current.player() == entry.player()) return;
      if (current != null) {
        ordered.remove(current);
        if (inFlight.get(playerId) == current.player()) inFlight.remove(playerId);
      }
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
        if (!inFlight.containsKey(playerId)) {
          inFlight.put(playerId, entry.player());
          claimed.add(entry);
          if (claimed.size() == limit) break;
        }
      }
      return List.copyOf(claimed);
    }

    synchronized void complete(Player player, boolean success) {
      UUID playerId = player.getUniqueId();
      if (inFlight.get(playerId) != player) return;
      inFlight.remove(playerId);
      if (success) removeInternal(playerId);
    }

    synchronized boolean remove(Player player) {
      UUID playerId = player.getUniqueId();
      SmartQueueEntry entry = byPlayer.get(playerId);
      if (entry == null || entry.player() != player) return false;
      if (inFlight.get(playerId) == player) inFlight.remove(playerId);
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
