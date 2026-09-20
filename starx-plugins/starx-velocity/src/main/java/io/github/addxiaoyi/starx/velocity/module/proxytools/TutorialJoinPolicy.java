package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.proxy.Player;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class TutorialJoinPolicy {
  private final TutorialProgressService progress;
  private final Set<String> prompted = ConcurrentHashMap.newKeySet();
  private final Map<String, Player> promptedPlayers = new ConcurrentHashMap<>();

  TutorialJoinPolicy(TutorialProgressService progress) {
    this.progress = Objects.requireNonNull(progress, "progress");
  }

  boolean shouldPrompt(String playerId) {
    if (this.progress.completed(playerId)) return false;
    return this.prompted.add(playerId);
  }

  boolean shouldPrompt(Player player) {
    Objects.requireNonNull(player, "player");
    String playerId = player.getUniqueId().toString();
    if (this.progress.completed(playerId)) return false;
    java.util.concurrent.atomic.AtomicBoolean shouldPrompt =
        new java.util.concurrent.atomic.AtomicBoolean();
    this.promptedPlayers.compute(playerId, (ignored, current) -> {
      if (current == player) return current;
      shouldPrompt.set(true);
      return player;
    });
    return shouldPrompt.get();
  }

  void release(String playerId) {
    this.prompted.remove(playerId);
  }

  void release(Player player) {
    Objects.requireNonNull(player, "player");
    String playerId = player.getUniqueId().toString();
    this.promptedPlayers.compute(playerId, (ignored, current) ->
        current == player ? null : current);
  }

  void clear() {
    this.prompted.clear();
    this.promptedPlayers.clear();
  }
}
