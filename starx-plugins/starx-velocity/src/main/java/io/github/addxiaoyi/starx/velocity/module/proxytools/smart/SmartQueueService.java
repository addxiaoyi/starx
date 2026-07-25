package io.github.addxiaoyi.starx.velocity.module.proxytools.smart;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SmartQueueService {
  private static final long MILLIS_PER_MINUTE = 60_000L;
  private static final Comparator<SmartQueueEntry> PRIORITY =
      Comparator.comparingLong(SmartQueueEntry::score).reversed()
          .thenComparingLong(SmartQueueEntry::enqueueTimeMs);

  private final Map<String, Bucket> queues = new ConcurrentHashMap<>();
  private final Map<UUID, Long> joinTimestamps = new ConcurrentHashMap<>();

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
    if (maxRelease < 0) throw new IllegalArgumentException("maxRelease must not be negative");
    int connected = 0;
    for (Map.Entry<String, Bucket> entry : queues.entrySet()) {
      if (connected >= maxRelease) break;
      connected += entry.getValue().process(
          connector, entry.getKey(), maxRelease - connected);
    }
    return connected;
  }

  private int computeActivityScore(Player player, long now) {
    Long joinedAt = joinTimestamps.get(player.getUniqueId());
    if (joinedAt == null) return 0;
    return (int) Math.min(100L, Math.max(0L, now - joinedAt) / MILLIS_PER_MINUTE);
  }

  public record SmartQueueEntry(Player player, long score, long enqueueTimeMs) { }

  @FunctionalInterface
  public interface PlayerConnector {
    boolean connect(Player player, String serverName);
  }

  private static final class Bucket {
    private final PriorityQueue<SmartQueueEntry> ordered = new PriorityQueue<>(PRIORITY);
    private final Map<UUID, SmartQueueEntry> byPlayer = new HashMap<>();

    synchronized void add(SmartQueueEntry entry) {
      UUID playerId = entry.player().getUniqueId();
      if (byPlayer.containsKey(playerId)) return;
      byPlayer.put(playerId, entry);
      ordered.add(entry);
    }

    synchronized SmartQueueEntry poll() {
      SmartQueueEntry entry = ordered.poll();
      if (entry != null) byPlayer.remove(entry.player().getUniqueId());
      return entry;
    }

    synchronized boolean remove(UUID playerId) {
      SmartQueueEntry entry = byPlayer.remove(playerId);
      return entry != null && ordered.remove(entry);
    }

    synchronized int size() {
      return byPlayer.size();
    }

    synchronized int position(UUID playerId) {
      List<SmartQueueEntry> snapshot = new ArrayList<>(ordered);
      snapshot.sort(PRIORITY);
      for (int index = 0; index < snapshot.size(); index++) {
        if (snapshot.get(index).player().getUniqueId().equals(playerId)) return index + 1;
      }
      return 0;
    }

    synchronized int process(PlayerConnector connector, String serverName, int limit) {
      int connected = 0;
      while (connected < limit) {
        SmartQueueEntry entry = ordered.peek();
        if (entry == null) break;
        if (!entry.player().isActive()) {
          poll();
          continue;
        }
        if (!connector.connect(entry.player(), serverName)) break;
        poll();
        connected++;
      }
      return connected;
    }
  }
}
