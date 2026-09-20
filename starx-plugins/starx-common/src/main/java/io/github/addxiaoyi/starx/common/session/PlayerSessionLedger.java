package io.github.addxiaoyi.starx.common.session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class PlayerSessionLedger {
  private final Map<UUID, ActiveSession> active = new ConcurrentHashMap<>();
  private final Map<UUID, PlayerTotals> totals = new ConcurrentHashMap<>();

  public void connected(UUID player, String server, long at) {
    ActiveSession existing = active.putIfAbsent(player, new ActiveSession(server, at));
    if (existing == null) {
      totals.computeIfAbsent(player, ignored -> new PlayerTotals()).loginCount.incrementAndGet();
    }
  }

  public void switched(UUID player, String server, long at) {
    ActiveSession current = active.get(player);
    if (current == null) { connected(player, server, at); return; }
    ActiveSession newSession = new ActiveSession(server, at);
    if (active.replace(player, current, newSession)) {
      accrue(player, current, at);
    }
  }

  public void disconnected(UUID player, long at, DisconnectReason reason) {
    ActiveSession current = active.remove(player);
    if (current == null) return;
    accrue(player, current, at);
    PlayerTotals playerTotals = totals.get(player);
    if (playerTotals != null) {
      playerTotals.lastDisconnect = reason;
    }
  }

  public long totalPlaytime(UUID player) { 
    PlayerTotals pt = totals.get(player); 
    return pt != null ? pt.total.sum() : 0L; 
  }
  public long playtime(UUID player, String server) {
    PlayerTotals pt = totals.get(player);
    return pt != null ? pt.byServer.getOrDefault(server, 0L) : 0L;
  }
  public int loginCount(UUID player) { 
    PlayerTotals pt = totals.get(player); 
    return pt != null ? pt.loginCount.get() : 0; 
  }
  public DisconnectReason lastDisconnect(UUID player) { 
    PlayerTotals pt = totals.get(player); 
    return pt != null ? pt.lastDisconnect : DisconnectReason.UNKNOWN; 
  }

  private void accrue(UUID player, ActiveSession session, long at) {
    long duration = Math.max(0L, at - session.startedAt);
    PlayerTotals result = totals.computeIfAbsent(player, ignored -> new PlayerTotals());
    result.total.add(duration);
    result.byServer.merge(session.server, duration, Long::sum);
  }

  private record ActiveSession(String server, long startedAt) {}
  private static final class PlayerTotals {
    private final LongAdder total = new LongAdder();
    private final AtomicInteger loginCount = new AtomicInteger();
    private volatile DisconnectReason lastDisconnect = DisconnectReason.UNKNOWN;
    private final Map<String, Long> byServer = new ConcurrentHashMap<>();
  }
}
