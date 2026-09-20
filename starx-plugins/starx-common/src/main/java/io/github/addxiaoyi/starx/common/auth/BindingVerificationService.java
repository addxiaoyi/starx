package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.common.binding.BindingChallenge;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeAction;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeService;
import io.github.addxiaoyi.starx.common.binding.JdbcBindingChallengeRepository.ChallengeTokenConflictException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

public final class BindingVerificationService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long CODE_TTL_MS = 300_000L;
  private static final int CODE_BOUND = 1_000_000;
  private static final int MAX_CODE_ALLOCATION_ATTEMPTS = 8;

  private final ConcurrentMap<String, PendingCode> pendingCodes = new ConcurrentHashMap<>();
  private final BindingChallengeService persistentChallenges;
  private final Function<UUID, String> accountByPlayer;
  private final Function<String, UUID> playerByAccount;
  private final Clock clock;
  private final Duration lifetime;
  private final Function<UUID, Set<UUID>> knownMinecraftUuids;
  private final IntSupplier codeGenerator;

  public BindingVerificationService() {
    this(uuid -> Set.of(uuid), Clock.systemUTC(), Duration.ofMillis(CODE_TTL_MS), RANDOM::nextInt);
  }

  public BindingVerificationService(
      Function<UUID, Set<UUID>> knownMinecraftUuids,
      Clock clock,
      Duration lifetime) {
    this(knownMinecraftUuids, clock, lifetime, RANDOM::nextInt);
  }

  BindingVerificationService(
      Function<UUID, Set<UUID>> knownMinecraftUuids,
      Clock clock,
      Duration lifetime,
      IntSupplier codeGenerator) {
    this.persistentChallenges = null;
    this.accountByPlayer = null;
    this.playerByAccount = null;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
    this.knownMinecraftUuids = Objects.requireNonNull(knownMinecraftUuids, "knownMinecraftUuids");
    this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    if (lifetime.isZero() || lifetime.isNegative()) {
      throw new IllegalArgumentException("lifetime must be positive");
    }
  }

  public BindingVerificationService(
      BindingChallengeService persistentChallenges,
      Function<UUID, String> accountByPlayer,
      Function<String, UUID> playerByAccount,
      Clock clock,
      Duration lifetime) {
    this(persistentChallenges, accountByPlayer, playerByAccount, clock, lifetime,
        uuid -> Set.of(uuid), RANDOM::nextInt);
  }

  public BindingVerificationService(
      BindingChallengeService persistentChallenges,
      Function<UUID, String> accountByPlayer,
      Function<String, UUID> playerByAccount,
      Clock clock,
      Duration lifetime,
      Function<UUID, Set<UUID>> knownMinecraftUuids) {
    this(persistentChallenges, accountByPlayer, playerByAccount, clock, lifetime,
        knownMinecraftUuids, RANDOM::nextInt);
  }

  BindingVerificationService(
      BindingChallengeService persistentChallenges,
      Function<UUID, String> accountByPlayer,
      Function<String, UUID> playerByAccount,
      Clock clock,
      Duration lifetime,
      Function<UUID, Set<UUID>> knownMinecraftUuids,
      IntSupplier codeGenerator) {
    this.persistentChallenges = Objects.requireNonNull(
        persistentChallenges, "persistentChallenges");
    this.accountByPlayer = Objects.requireNonNull(accountByPlayer, "accountByPlayer");
    this.playerByAccount = Objects.requireNonNull(playerByAccount, "playerByAccount");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
    this.knownMinecraftUuids = Objects.requireNonNull(knownMinecraftUuids, "knownMinecraftUuids");
    this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    if (lifetime.isZero() || lifetime.isNegative()) {
      throw new IllegalArgumentException("lifetime must be positive");
    }
  }

  public String generateCode(UUID playerUuid) {
    UUID requestedPlayer = Objects.requireNonNull(playerUuid, "playerUuid");
    if (this.persistentChallenges != null) {
      String accountId = this.accountByPlayer.apply(
          requestedPlayer);
      if (accountId == null || accountId.isBlank()) {
        throw new IllegalArgumentException("Player account identity is not registered");
      }
      Instant now = this.clock.instant();
      ChallengeTokenConflictException conflict = null;
      for (int attempt = 0; attempt < MAX_CODE_ALLOCATION_ATTEMPTS; attempt++) {
        String code = nextCode();
        try {
          this.persistentChallenges.beginReplacingActive(
              accountId, "QQ", null, code, now, this.lifetime);
          return code;
        } catch (ChallengeTokenConflictException error) {
          conflict = error;
        }
      }
      throw new IllegalStateException("Unable to allocate a unique verification code", conflict);
    }
    synchronized (this.pendingCodes) {
      for (int attempt = 0; attempt < MAX_CODE_ALLOCATION_ATTEMPTS; attempt++) {
        String code = nextCode();
        PendingCode pending = new PendingCode(
            UUID.randomUUID().toString(),
            requestedPlayer,
            this.clock.millis() + this.lifetime.toMillis());
        if (this.pendingCodes.putIfAbsent(code, pending) != null) continue;
        this.pendingCodes.entrySet().removeIf(entry ->
            !entry.getKey().equals(code) && entry.getValue().playerUuid().equals(requestedPlayer));
        return code;
      }
    }
    throw new IllegalStateException("Unable to allocate a unique verification code");
  }

  private String nextCode() {
    return "%06d".formatted(Math.floorMod(this.codeGenerator.getAsInt(), CODE_BOUND));
  }

  public UUID verifyCode(String code) {
    return this.verifyCodeIf(code, ignored -> true);
  }

  public UUID verifyCodeIf(String code, Predicate<UUID> acceptance) {
    if (code == null || acceptance == null) return null;
    if (this.persistentChallenges != null) {
      Instant now = this.clock.instant();
      BindingChallenge challenge =
          this.persistentChallenges.inspectExecutable("QQ", code, now);
      if (challenge == null) return null;
      UUID playerId = this.playerByAccount.apply(challenge.accountId());
      if (playerId == null || !acceptance.test(playerId)) return null;
      BindingChallengeService.Execution execution =
          this.persistentChallenges.acquire(challenge, now);
      if (execution == null) return null;
      return this.persistentChallenges.consume(execution, now) ? playerId : null;
    }

    AtomicReference<UUID> verified = new AtomicReference<>();
    this.pendingCodes.computeIfPresent(code, (ignored, pending) -> {
      if (this.clock.millis() >= pending.expiresAt()) return null;
      boolean accepted = false;
      for (UUID knownUuid : this.knownMinecraftUuids(pending.playerUuid())) {
        if (acceptance.test(knownUuid)) {
          accepted = true;
          break;
        }
      }
      if (!accepted) return pending;
      verified.set(pending.playerUuid());
      return null;
    });
    return verified.get();
  }

  public UUID verifyAndExecute(String code, Predicate<UUID> action) {
    if (action == null) return null;
    return verifyAndExecute(
        code, (ignoredOperationId, playerId) -> action.test(playerId));
  }

  public UUID verifyAndExecute(
      String code, BindingChallengeAction<UUID> action) {
    if (code == null || action == null) return null;
    if (this.persistentChallenges == null) {
      AtomicReference<UUID> verified = new AtomicReference<>();
      this.pendingCodes.computeIfPresent(code, (ignored, pending) -> {
        if (this.clock.millis() >= pending.expiresAt()) return null;
        if (!action.execute(pending.operationId(), pending.playerUuid())) return pending;
        verified.set(pending.playerUuid());
        return null;
      });
      return verified.get();
    }

    Instant now = this.clock.instant();
    BindingChallenge challenge =
        this.persistentChallenges.inspectExecutable("QQ", code, now);
    if (challenge == null) return null;
    UUID playerId = this.playerByAccount.apply(challenge.accountId());
    if (playerId == null) return null;
    BindingChallengeService.Execution execution =
        this.persistentChallenges.acquire(challenge, now);
    if (execution == null) return null;

    boolean executed;
    try {
      executed = action.execute(execution.operationId(), playerId);
    } catch (RuntimeException error) {
      this.persistentChallenges.release(execution, this.clock.instant());
      throw error;
    }
    Instant completedAt = this.clock.instant();
    if (!executed) {
      this.persistentChallenges.release(execution, completedAt);
      return null;
    }
    return this.persistentChallenges.consume(execution, completedAt) ? playerId : null;
  }

  private record PendingCode(String operationId, UUID playerUuid, long expiresAt) {}

  private Set<UUID> knownMinecraftUuids(UUID requestedPlayerId) {
    Set<UUID> resolved = this.knownMinecraftUuids.apply(requestedPlayerId);
    if (resolved == null || resolved.isEmpty()) return Set.of(requestedPlayerId);
    java.util.LinkedHashSet<UUID> known = new java.util.LinkedHashSet<>();
    known.add(requestedPlayerId);
    for (UUID uuid : resolved) known.add(Objects.requireNonNull(uuid, "resolved uuid"));
    return Set.copyOf(known);
  }
}
