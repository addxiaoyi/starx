package io.github.addxiaoyi.starx.common.binding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class BindingChallengeService {
  private static final Duration DEFAULT_EXECUTION_LEASE = Duration.ofSeconds(30);

  private final JdbcBindingChallengeRepository challenges;
  private final Duration executionLease;

  public BindingChallengeService(JdbcBindingChallengeRepository challenges) {
    this(challenges, DEFAULT_EXECUTION_LEASE);
  }

  public BindingChallengeService(
      JdbcBindingChallengeRepository challenges, Duration executionLease) {
    this.challenges = Objects.requireNonNull(challenges, "challenges");
    this.executionLease = Objects.requireNonNull(executionLease, "executionLease");
    if (executionLease.isZero() || executionLease.isNegative()) {
      throw new IllegalArgumentException("executionLease must be positive");
    }
  }

  public String begin(
      String accountId, String kind, String rawToken, Instant now, Duration lifetime) {
    return begin(accountId, kind, null, rawToken, now, lifetime);
  }

  public String begin(
      String accountId, String kind, String payload, String rawToken, Instant now, Duration lifetime) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(lifetime, "lifetime");
    if (lifetime.isZero() || lifetime.isNegative()) {
      throw new IllegalArgumentException("lifetime must be positive");
    }
    long createdAt = now.toEpochMilli();
    String id = challenges.create(
        accountId, kind, payload, hash(kind, requireToken(rawToken)), createdAt,
        now.plus(lifetime).toEpochMilli());
    if (!challenges.transition(id, BindingState.CREATED, BindingAction.SEND, createdAt)) {
      throw new IllegalStateException("Failed to activate binding challenge");
    }
    return id;
  }

  public String beginReplacingActive(
      String accountId, String kind, String payload, String rawToken, Instant now, Duration lifetime) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(lifetime, "lifetime");
    if (lifetime.isZero() || lifetime.isNegative()) {
      throw new IllegalArgumentException("lifetime must be positive");
    }
    long createdAt = now.toEpochMilli();
    String id = challenges.createReplacingActive(
        accountId, kind, payload, hash(kind, requireToken(rawToken)), createdAt,
        now.plus(lifetime).toEpochMilli());
    if (!challenges.transition(id, BindingState.CREATED, BindingAction.SEND, createdAt)) {
      throw new IllegalStateException("Failed to activate binding challenge");
    }
    return id;
  }

  public boolean confirm(String id, String rawToken, Instant now) {
    Objects.requireNonNull(now, "now");
    BindingChallenge challenge = challenges.find(requireText(id, "id")).orElse(null);
    if (challenge == null || challenge.state() != BindingState.SENT) return false;
    if (now.toEpochMilli() >= challenge.expiresAt()) {
      challenges.transition(id, BindingState.SENT, BindingAction.EXPIRE, now.toEpochMilli());
      return false;
    }
    byte[] expected = challenge.tokenHash().getBytes(StandardCharsets.US_ASCII);
    byte[] supplied = hash(challenge.kind(), requireToken(rawToken)).getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(expected, supplied)) return false;
    return challenges.transition(id, BindingState.SENT, BindingAction.CONFIRM, now.toEpochMilli());
  }

  public BindingChallenge inspect(String kind, String rawToken, Instant now) {
    Objects.requireNonNull(now, "now");
    BindingChallenge challenge = challenges.findSent(
        requireText(kind, "kind"), hash(kind, requireToken(rawToken))).orElse(null);
    if (challenge == null) return null;
    if (now.toEpochMilli() < challenge.expiresAt()) return challenge;
    challenges.transition(
        challenge.id(), BindingState.SENT, BindingAction.EXPIRE, now.toEpochMilli());
    return null;
  }

  public BindingChallenge inspectExecutable(String kind, String rawToken, Instant now) {
    Objects.requireNonNull(now, "now");
    BindingChallenge challenge = challenges.findExecutable(
        requireText(kind, "kind"), hash(kind, requireToken(rawToken))).orElse(null);
    if (challenge == null) return null;
    long nowMillis = now.toEpochMilli();
    if (nowMillis < challenge.expiresAt()) return challenge;
    if (challenge.state() == BindingState.SENT || challenge.state() == BindingState.CONFIRMED) {
      challenges.transition(
          challenge.id(), challenge.state(), BindingAction.EXPIRE, nowMillis);
    }
    return null;
  }

  public Execution acquire(BindingChallenge challenge, Instant now) {
    Objects.requireNonNull(challenge, "challenge");
    Objects.requireNonNull(now, "now");
    String owner = UUID.randomUUID().toString();
    long leaseUntil = now.plus(this.executionLease).toEpochMilli();
    return challenges.acquireExecution(
        challenge.id(), owner, now.toEpochMilli(), leaseUntil)
        ? new Execution(challenge, owner) : null;
  }

  public boolean consume(Execution execution, Instant now) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(now, "now");
    boolean consumed = challenges.completeExecution(
        execution.challenge().id(), execution.owner(), BindingAction.CONSUME,
        now.toEpochMilli());
    if (!consumed && now.toEpochMilli() >= execution.challenge().expiresAt()) {
      challenges.transition(
          execution.challenge().id(), BindingState.CONFIRMED, BindingAction.EXPIRE,
          now.toEpochMilli());
    }
    return consumed;
  }

  public boolean release(Execution execution, Instant now) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(now, "now");
    boolean released = challenges.completeExecution(
        execution.challenge().id(), execution.owner(), BindingAction.RELEASE,
        now.toEpochMilli());
    if (!released && now.toEpochMilli() >= execution.challenge().expiresAt()) {
      challenges.transition(
          execution.challenge().id(), BindingState.CONFIRMED, BindingAction.EXPIRE,
          now.toEpochMilli());
    }
    return released;
  }

  public boolean consume(String id, Instant now) {
    Objects.requireNonNull(now, "now");
    return challenges.transition(
        requireText(id, "id"), BindingState.CONFIRMED, BindingAction.CONSUME, now.toEpochMilli());
  }

  public boolean revoke(String id, Instant now) {
    Objects.requireNonNull(now, "now");
    BindingChallenge challenge = challenges.find(requireText(id, "id")).orElse(null);
    if (challenge == null) return false;
    return switch (challenge.state()) {
      case CREATED, SENT, CONFIRMED -> challenges.transition(
          challenge.id(), challenge.state(), BindingAction.REVOKE, now.toEpochMilli());
      case CONSUMED, EXPIRED, REVOKED -> false;
    };
  }

  public boolean release(String id, Instant now) {
    Objects.requireNonNull(now, "now");
    return challenges.transition(
        requireText(id, "id"), BindingState.CONFIRMED, BindingAction.RELEASE, now.toEpochMilli());
  }

  private static String hash(String kind, String token) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(
              (requireText(kind, "kind") + "\0" + token).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static String requireToken(String token) {
    return requireText(token, "token");
  }

  private static String requireText(String value, String field) {
    String text = Objects.requireNonNull(value, field).trim();
    if (text.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return text;
  }

  public record Execution(BindingChallenge challenge, String owner) {
    public Execution {
      Objects.requireNonNull(challenge, "challenge");
      owner = requireText(owner, "owner");
    }

    public String operationId() {
      return this.challenge.id();
    }
  }
}
