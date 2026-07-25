package io.github.addxiaoyi.starx.velocity.module.proxytools.queue;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QueueService {
  private final Map<String, Bucket> queues = new ConcurrentHashMap<>();

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
    int connected = 0;
    for (Map.Entry<String, Bucket> entry : queues.entrySet()) {
      Bucket bucket = entry.getValue();
      if (!bucket.beginProcessing()) continue;
      try {
        while (true) {
          Player player = bucket.peek();
          if (player == null) break;
          if (!player.isActive()) {
            bucket.removeHead(player);
            continue;
          }
          if (!connector.connect(player, entry.getKey())) break;
          if (bucket.removeHead(player)) connected++;
        }
      } finally {
        bucket.endProcessing();
      }
    }
    return connected;
  }

  private static String serverName(RegisteredServer server) {
    return server.getServerInfo().getName();
  }

  @FunctionalInterface
  public interface PlayerConnector {
    boolean connect(Player player, String serverName);
  }

  private static final class Bucket {
    private final ConcurrentLinkedDeque<Player> players = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<UUID, Boolean> members = new ConcurrentHashMap<>();
    private final AtomicBoolean processing = new AtomicBoolean();

    boolean beginProcessing() {
      return processing.compareAndSet(false, true);
    }

    void endProcessing() {
      processing.set(false);
    }

    void add(Player player) {
      if (members.putIfAbsent(player.getUniqueId(), Boolean.TRUE) == null) players.addLast(player);
    }

    Player peek() {
      return players.peekFirst();
    }

    Player poll() {
      Player player = players.pollFirst();
      if (player != null) members.remove(player.getUniqueId());
      return player;
    }

    boolean removeHead(Player expected) {
      if (!players.removeFirstOccurrence(expected)) return false;
      members.remove(expected.getUniqueId());
      return true;
    }

    boolean remove(UUID playerId) {
      if (members.remove(playerId) == null) return false;
      players.removeIf(player -> player.getUniqueId().equals(playerId));
      return true;
    }

    int size() {
      return members.size();
    }

    int position(UUID playerId) {
      int position = 0;
      for (Player player : players) {
        position++;
        if (player.getUniqueId().equals(playerId)) return position;
      }
      return 0;
    }
  }
}
