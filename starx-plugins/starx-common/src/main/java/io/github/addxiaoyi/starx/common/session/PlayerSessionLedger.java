package io.github.addxiaoyi.starx.common.session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerSessionLedger {
  private final Map<UUID, ActiveSession> active = new HashMap<>();
  private final Map<UUID, PlayerTotals> totals = new HashMap<>();

  public synchronized void connected(UUID player, String server, long at) {
    if (active.containsKey(player)) return;
    active.put(player, new ActiveSession(server, at));
    totals.computeIfAbsent(player, ignored -> new PlayerTotals()).loginCount++;
  }

  public synchronized void switched(UUID player, String server, long at) {
    ActiveSession current = active.get(player);
    if (current == null) { connected(player, server, at); return; }
    accrue(player, current, at);
    active.put(player, new ActiveSession(server, at));
  }

  public synchronized void disconnected(UUID player, long at, DisconnectReason reason) {
    ActiveSession current = active.remove(player);
    if (current == null) return;
    accrue(player, current, at);
    totals.get(player).lastDisconnect = reason;
  }

  public synchronized long totalPlaytime(UUID player) { return totals.getOrDefault(player, new PlayerTotals()).total; }
  public synchronized long playtime(UUID player, String server) {
    return totals.getOrDefault(player, new PlayerTotals()).byServer.getOrDefault(server, 0L);
  }
  public synchronized int loginCount(UUID player) { return totals.getOrDefault(player, new PlayerTotals()).loginCount; }
  public synchronized DisconnectReason lastDisconnect(UUID player) { return totals.getOrDefault(player, new PlayerTotals()).lastDisconnect; }

  private void accrue(UUID player, ActiveSession session, long at) {
    long duration = Math.max(0L, at - session.startedAt);
    PlayerTotals result = totals.computeIfAbsent(player, ignored -> new PlayerTotals());
    result.total += duration;
    result.byServer.merge(session.server, duration, Long::sum);
  }

  private record ActiveSession(String server, long startedAt) {}
  private static final class PlayerTotals {
    private long total;
    private int loginCount;
    private DisconnectReason lastDisconnect = DisconnectReason.UNKNOWN;
    private final Map<String, Long> byServer = new HashMap<>();
  }
}
