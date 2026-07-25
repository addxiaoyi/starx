package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import io.github.addxiaoyi.starx.common.database.JdbcTutorialProgressRepository;

/** Tracks tutorial progress without coupling the flow to chat or command APIs. */
public final class TutorialProgressService {
  private final int stepCount;
  private final Map<String, Integer> progress = new ConcurrentHashMap<>();
  private final JdbcTutorialProgressRepository repository;

  public TutorialProgressService(int stepCount) {
    this(stepCount, null);
  }

  public TutorialProgressService(
      int stepCount, JdbcTutorialProgressRepository repository) {
    if (stepCount < 1) throw new IllegalArgumentException("stepCount must be positive");
    this.stepCount = stepCount;
    this.repository = repository;
  }

  public int step(String playerId) {
    String id = normalize(playerId);
    return repository == null ? progress.getOrDefault(id, 0) : repository.step(UUID.fromString(id));
  }

  public int advance(String playerId) {
    String id = normalize(playerId);
    if (repository != null) {
      return repository.advance(UUID.fromString(id), stepCount, System.currentTimeMillis());
    }
    return progress.compute(id, (ignored, current) -> Math.min(stepCount, (current == null ? 0 : current) + 1));
  }

  public boolean completed(String playerId) {
    return step(playerId) >= stepCount;
  }

  public int complete(String playerId) {
    String id = normalize(playerId);
    if (repository != null) {
      return repository.complete(UUID.fromString(id), stepCount, System.currentTimeMillis());
    }
    progress.put(id, stepCount);
    return stepCount;
  }

  public void reset(String playerId) {
    String id = normalize(playerId);
    if (repository == null) {
      progress.remove(id);
    } else {
      repository.reset(UUID.fromString(id));
    }
  }

  private String normalize(String playerId) {
    String id = Objects.requireNonNull(playerId, "playerId").trim();
    if (id.isEmpty()) throw new IllegalArgumentException("playerId must not be blank");
    return id;
  }
}
