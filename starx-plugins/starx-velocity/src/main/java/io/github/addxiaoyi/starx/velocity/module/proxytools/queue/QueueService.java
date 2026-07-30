package io.github.addxiaoyi.starx.velocity.module.proxytools.queue;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QueueService {
  private final Map<String, Bucket> queues = new ConcurrentHashMap<>();
  private final AtomicBoolean dispatching = new AtomicBoolean();

  public void enqueue(RegisteredServer server, Player player) {
    String name = serverName(server);
    queues.computeIfAbsent(name, ignored -> new Bucket()).add(player);
  }

  public Player dequeue(RegisteredServer server) {
    Bucket bucket = queues.get(serverName(server));
    return bucket == null ? null : bucket.poll();
  }

  public int size(RegisteredServer server) {
    Bucket bucket = queues.get(serverName(server));
    return bucket == null ? 0 : bucket.size();
  }

  public Map<String, Integer> snapshot() {
    Map<String, Integer> sizes = new java.util.TreeMap<>();
    queues.forEach((server, bucket) -> sizes.put(server, bucket.size()));
    return Map.copyOf(sizes);
  }

  public void clear() {
    queues.clear();
  }

  public int position(RegisteredServer server, Player player) {
    Bucket bucket = queues.get(serverName(server));
    return bucket == null ? 0 : bucket.position(player.getUniqueId());
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
    Bucket bucket = queues.get(serverName(server));
    return bucket != null && bucket.remove(player.getUniqueId());
  }

  public int removeFromAllQueues(Player player) {
    int removed = 0;
    UUID playerId = player.getUniqueId();
    for (Bucket bucket : queues.values()) {
      if (bucket.remove(playerId)) removed++;
    }
    return removed;
  }

  public int processQueues(PlayerConnector connector) {
    Objects.requireNonNull(connector, "connector");
    if (!this.dispatching.compareAndSet(false, true)) return 0;
    int dispatched = 0;
    try {
      for (Map.Entry<String, Bucket> entry : queues.entrySet()) {
        Bucket bucket = entry.getValue();
        Player player = bucket.claimNext();
        if (player == null) continue;
        UUID playerId = player.getUniqueId();
        CompletionStage<Boolean> result;
        try {
          result = connector.connect(player, entry.getKey());
        } catch (RuntimeException error) {
          bucket.complete(playerId, false);
          continue;
        }
        if (result == null) {
          bucket.complete(playerId, false);
          continue;
        }
        dispatched++;
        result.whenComplete((success, error) ->
            bucket.complete(playerId, error == null && Boolean.TRUE.equals(success)));
      }
    } finally {
      this.dispatching.set(false);
    }
    return dispatched;
  }

  private static String serverName(RegisteredServer server) {
    return server.getServerInfo().getName();
  }

  @FunctionalInterface
  public interface PlayerConnector {
    CompletionStage<Boolean> connect(Player player, String serverName);
  }

  private static final class Bucket {
    private final ConcurrentLinkedDeque<Player> players = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<UUID, Boolean> members = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    synchronized void add(Player player) {
      if (members.putIfAbsent(player.getUniqueId(), Boolean.TRUE) == null) {
        players.addLast(player);
      }
    }

    synchronized Player claimNext() {
      for (Player player : new ArrayList<>(players)) {
        UUID playerId = player.getUniqueId();
        if (!members.containsKey(playerId)) continue;
        if (!player.isActive()) {
          remove(playerId);
          continue;
        }
        if (inFlight.add(playerId)) return player;
      }
      return null;
    }

    synchronized void complete(UUID playerId, boolean success) {
      if (!inFlight.remove(playerId)) return;
      if (!success) return;
      if (members.remove(playerId) != null) {
        players.removeIf(player -> player.getUniqueId().equals(playerId));
      }
    }

    synchronized Player poll() {
      Player player = players.pollFirst();
      if (player != null) {
        UUID playerId = player.getUniqueId();
        members.remove(playerId);
        inFlight.remove(playerId);
      }
      return player;
    }

    synchronized boolean remove(UUID playerId) {
      inFlight.remove(playerId);
      if (members.remove(playerId) == null) return false;
      players.removeIf(player -> player.getUniqueId().equals(playerId));
      return true;
    }

    synchronized int size() {
      return members.size();
    }

    synchronized int position(UUID playerId) {
      int position = 0;
      for (Player player : players) {
        position++;
        if (player.getUniqueId().equals(playerId)) return position;
      }
      return 0;
    }
  }
}
