package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.Set;
import java.util.function.Function;
import io.github.addxiaoyi.starx.common.database.JdbcTutorialProgressRepository;

/** Tracks tutorial progress without coupling the flow to chat or command APIs. */
public final class TutorialProgressService {
  private final int stepCount;
  private final Map<String, Integer> progress = new ConcurrentHashMap<>();
  private final JdbcTutorialProgressRepository repository;
  private final Function<UUID, UUID> canonicalUuidResolver;
  private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;

  public TutorialProgressService(int stepCount) {
    this(stepCount, null);
  }

  public TutorialProgressService(
      int stepCount, JdbcTutorialProgressRepository repository) {
    this(stepCount, repository, uuid -> uuid, uuid -> Set.of(uuid));
  }

  public TutorialProgressService(
      int stepCount,
      JdbcTutorialProgressRepository repository,
      Function<UUID, UUID> canonicalUuidResolver,
      Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
    if (stepCount < 1) throw new IllegalArgumentException("stepCount must be positive");
    this.stepCount = stepCount;
    this.repository = repository;
    this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
    this.knownMinecraftUuidsResolver = Objects.requireNonNull(
        knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
  }

  public int step(String playerId) {
    String id = normalize(playerId);
    if (repository == null) return progress.getOrDefault(id, 0);
    UUID current = UUID.fromString(id);
    return repository.step(normalizeKnownMinecraftUuids(
        current, this.knownMinecraftUuidsResolver.apply(current)));
  }

  public int advance(String playerId) {
    String id = normalize(playerId);
    if (repository != null) {
      UUID current = UUID.fromString(id);
      return repository.advance(
          normalizeCanonicalUuid(current, this.canonicalUuidResolver.apply(current)),
          normalizeKnownMinecraftUuids(
              current, this.knownMinecraftUuidsResolver.apply(current)),
          stepCount,
          System.currentTimeMillis());
    }
    return progress.compute(id, (ignored, current) -> Math.min(stepCount, (current == null ? 0 : current) + 1));
  }

  public boolean completed(String playerId) {
    return step(playerId) >= stepCount;
  }

  public int complete(String playerId) {
    String id = normalize(playerId);
    if (repository != null) {
      UUID current = UUID.fromString(id);
      return repository.complete(
          normalizeCanonicalUuid(current, this.canonicalUuidResolver.apply(current)),
          normalizeKnownMinecraftUuids(
              current, this.knownMinecraftUuidsResolver.apply(current)),
          stepCount,
          System.currentTimeMillis());
    }
    progress.put(id, stepCount);
    return stepCount;
  }

  public void reset(String playerId) {
    String id = normalize(playerId);
    if (repository == null) {
      progress.remove(id);
    } else {
      UUID current = UUID.fromString(id);
      repository.reset(normalizeKnownMinecraftUuids(
          current, this.knownMinecraftUuidsResolver.apply(current)));
    }
  }

  static Set<UUID> normalizeKnownMinecraftUuids(UUID current, Set<UUID> resolved) {
    Objects.requireNonNull(current, "current");
    LinkedHashSet<UUID> known = new LinkedHashSet<>();
    known.add(current);
    if (resolved != null) {
      for (UUID uuid : resolved) known.add(Objects.requireNonNull(uuid, "resolved uuid"));
    }
    return Set.copyOf(known);
  }

  static UUID normalizeCanonicalUuid(UUID current, UUID resolved) {
    Objects.requireNonNull(current, "current");
    return resolved == null ? current : resolved;
  }

  private String normalize(String playerId) {
    String id = Objects.requireNonNull(playerId, "playerId").trim();
    if (id.isEmpty()) throw new IllegalArgumentException("playerId must not be blank");
    return id;
  }
}
