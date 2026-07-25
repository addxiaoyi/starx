package io.github.addxiaoyi.starx.limbo;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class LimboPlayerState<P, Q, C, S> {

  private final Set<P> joined = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<P, Q> loginQueues = new ConcurrentHashMap<>();
  private final ConcurrentMap<P, C> kickCallbacks = new ConcurrentHashMap<>();
  private final ConcurrentMap<P, S> nextServers = new ConcurrentHashMap<>();

  boolean join(P player, Runnable onFirstJoin) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(onFirstJoin, "onFirstJoin");
    if (!this.joined.add(player)) {
      return false;
    }

    try {
      onFirstJoin.run();
      return true;
    } catch (RuntimeException | Error error) {
      this.joined.remove(player);
      throw error;
    }
  }

  boolean leave(P player) {
    return this.joined.remove(player);
  }

  boolean isJoined(P player) {
    return this.joined.contains(player);
  }

  int joinedCount() {
    return this.joined.size();
  }

  void setLoginQueue(P player, Q queue) {
    this.loginQueues.put(player, queue);
  }

  Q loginQueue(P player) {
    return this.loginQueues.get(player);
  }

  void removeLoginQueue(P player) {
    this.loginQueues.remove(player);
  }

  void setKickCallback(P player, C callback) {
    this.kickCallbacks.put(player, callback);
  }

  C kickCallback(P player) {
    return this.kickCallbacks.get(player);
  }

  void removeKickCallback(P player) {
    this.kickCallbacks.remove(player);
  }

  void setNextServer(P player, S server) {
    this.nextServers.put(player, server);
  }

  S nextServer(P player) {
    return this.nextServers.get(player);
  }

  S takeNextServer(P player) {
    return this.nextServers.remove(player);
  }

  boolean hasNextServer(P player) {
    return this.nextServers.containsKey(player);
  }

  void removeNextServer(P player) {
    this.nextServers.remove(player);
  }

  void clear() {
    this.joined.clear();
    this.loginQueues.clear();
    this.kickCallbacks.clear();
    this.nextServers.clear();
  }
}
