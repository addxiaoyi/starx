package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class TutorialJoinPolicy {
  private final TutorialProgressService progress;
  private final Set<String> prompted = ConcurrentHashMap.newKeySet();

  TutorialJoinPolicy(TutorialProgressService progress) {
    this.progress = Objects.requireNonNull(progress, "progress");
  }

  boolean shouldPrompt(String playerId) {
    if (this.progress.completed(playerId)) return false;
    return this.prompted.add(playerId);
  }

  void release(String playerId) {
    this.prompted.remove(playerId);
  }

  void clear() {
    this.prompted.clear();
  }
}
