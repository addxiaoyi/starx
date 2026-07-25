package io.github.addxiaoyi.starx.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import io.github.addxiaoyi.starx.common.binding.BindingChallenge;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeService;

public final class CrossDeviceApprovalService {
  private static final int TOKEN_BYTES = 32;

  private final Clock clock;
  private final Duration ttl;
  private final TokenGenerator tokens;
  private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
  private final BindingChallengeService persistentChallenges;
  private final Function<UUID, String> accountByPlayer;
  private final Function<String, UUID> playerByAccount;
  private final Function<String, String> nameByAccount;

  public CrossDeviceApprovalService() {
    this(Clock.systemUTC(), Duration.ofMinutes(5), secureTokenGenerator());
  }

  public CrossDeviceApprovalService(
      BindingChallengeService persistentChallenges,
      Function<UUID, String> accountByPlayer,
      Function<String, UUID> playerByAccount,
      Function<String, String> nameByAccount) {
    this(Clock.systemUTC(), Duration.ofMinutes(5), secureTokenGenerator(),
        persistentChallenges, accountByPlayer, playerByAccount, nameByAccount);
  }

  CrossDeviceApprovalService(Clock clock, Duration ttl, TokenGenerator tokens) {
    this(clock, ttl, tokens, null, null, null, null);
  }

  public CrossDeviceApprovalService(
      Clock clock,
      Duration ttl,
      TokenGenerator tokens,
      BindingChallengeService persistentChallenges,
      Function<UUID, String> accountByPlayer,
      Function<String, UUID> playerByAccount,
      Function<String, String> nameByAccount) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.persistentChallenges = persistentChallenges;
    this.accountByPlayer = accountByPlayer;
    this.playerByAccount = playerByAccount;
    this.nameByAccount = nameByAccount;
    if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Approval TTL must be positive");
    if (persistentChallenges != null) {
      Objects.requireNonNull(accountByPlayer, "accountByPlayer");
      Objects.requireNonNull(playerByAccount, "playerByAccount");
      Objects.requireNonNull(nameByAccount, "nameByAccount");
    }
  }

  public Challenge create(UUID playerId, String username, Action action) {
    if (action == Action.APPROVE_LOGIN) {
      throw new IllegalArgumentException("Login approval requires an authentication lease");
    }
    return this.create(playerId, username, action, null);
  }

  public Challenge createLogin(UUID playerId, String username, AuthLease lease) {
    return this.create(
        playerId, username, Action.APPROVE_LOGIN,
        Objects.requireNonNull(lease, "lease"));
  }

  private Challenge create(
      UUID playerId, String username, Action action, AuthLease authLease) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(action, "action");
    String playerName = normalizeName(username);
    String token = Objects.requireNonNull(tokens.next(), "token").trim();
    if (token.length() < 32) throw new IllegalStateException("Approval token has insufficient entropy");
    Instant expiresAt = this.clock.instant().plus(this.ttl);
    if (this.persistentChallenges != null) {
      String accountId = this.accountByPlayer.apply(playerId);
      if (accountId == null || accountId.isBlank()) {
        throw new IllegalArgumentException("Player account identity is not registered");
      }
      this.persistentChallenges.begin(
          accountId, kind(action), leasePayload(authLease), token,
          this.clock.instant(), this.ttl);
      return new Challenge(token, playerId, playerName, action, expiresAt, authLease);
    }
    this.pending.put(hash(token), new Pending(
        playerId, playerName, action, expiresAt, authLease));
    return new Challenge(token, playerId, playerName, action, expiresAt, authLease);
  }

  public Approval approve(String token, UUID playerId, String username, Action action) {
    return this.approveAndExecute(token, playerId, username, action, ignored -> true);
  }

  public Approval approveAndExecute(
      String token,
      UUID playerId,
      String username,
      Action action,
      Function<Challenge, Boolean> executor
  ) {
    if (token == null || token.isBlank() || playerId == null || action == null) {
      return new Approval(Status.UNKNOWN);
    }
    Objects.requireNonNull(executor, "executor");
    if (this.persistentChallenges != null) {
      return approvePersistent(token.trim(), playerId, username, action, executor);
    }
    String tokenHash = hash(token.trim());
    Pending challenge = this.pending.get(tokenHash);
    if (challenge == null) return new Approval(Status.UNKNOWN);
    if (!challenge.expiresAt().isAfter(this.clock.instant())) {
      this.pending.remove(tokenHash, challenge);
      return new Approval(Status.EXPIRED);
    }
    boolean matches = challenge.playerId().equals(playerId)
        && challenge.username().equals(normalizeName(username))
        && challenge.action() == action;
    if (!matches) return new Approval(Status.MISMATCH);
    synchronized (challenge) {
      if (this.pending.get(tokenHash) != challenge) return new Approval(Status.UNKNOWN);
      boolean executed;
      try {
          executed = Boolean.TRUE.equals(executor.apply(new Challenge(
            token.trim(), challenge.playerId(), challenge.username(), challenge.action(),
            challenge.expiresAt(), challenge.authLease())));
      } catch (RuntimeException error) {
        executed = false;
      }
      if (!executed) return new Approval(Status.EXECUTION_FAILED);
      return this.pending.remove(tokenHash, challenge)
          ? new Approval(Status.APPROVED)
          : new Approval(Status.UNKNOWN);
    }
  }

  public Approval cancel(String token, UUID playerId, String username) {
    if (token == null || token.isBlank() || playerId == null) {
      return new Approval(Status.UNKNOWN);
    }
    if (this.persistentChallenges != null) {
      for (Action action : Action.values()) {
        BindingChallenge challenge = this.persistentChallenges.inspect(
            kind(action), token.trim(), this.clock.instant());
        if (challenge == null) continue;
        UUID expectedPlayer = this.playerByAccount.apply(challenge.accountId());
        String expectedName = this.nameByAccount.apply(challenge.accountId());
        if (!playerId.equals(expectedPlayer)
            || !normalizeName(username).equals(normalizeName(expectedName))) {
          return new Approval(Status.MISMATCH);
        }
        return this.persistentChallenges.revoke(challenge.id(), this.clock.instant())
            ? new Approval(Status.CANCELLED) : new Approval(Status.UNKNOWN);
      }
      return new Approval(Status.UNKNOWN);
    }
    String tokenHash = hash(token.trim());
    Pending challenge = this.pending.get(tokenHash);
    if (challenge == null) return new Approval(Status.UNKNOWN);
    if (!challenge.expiresAt().isAfter(this.clock.instant())) {
      this.pending.remove(tokenHash, challenge);
      return new Approval(Status.EXPIRED);
    }
    boolean matches = challenge.playerId().equals(playerId)
        && challenge.username().equals(normalizeName(username));
    if (!matches) return new Approval(Status.MISMATCH);
    synchronized (challenge) {
      return this.pending.remove(tokenHash, challenge)
          ? new Approval(Status.CANCELLED)
          : new Approval(Status.UNKNOWN);
    }
  }

  private Approval approvePersistent(
      String token,
      UUID playerId,
      String username,
      Action action,
      Function<Challenge, Boolean> executor) {
    Instant now = this.clock.instant();
    BindingChallenge stored = this.persistentChallenges.inspect(kind(action), token, now);
    if (stored == null) return new Approval(Status.UNKNOWN);
    UUID expectedPlayer = this.playerByAccount.apply(stored.accountId());
    String expectedName = this.nameByAccount.apply(stored.accountId());
    if (!playerId.equals(expectedPlayer)
        || !normalizeName(username).equals(normalizeName(expectedName))) {
      return new Approval(Status.MISMATCH);
    }
    Challenge challenge = new Challenge(
        token, expectedPlayer, normalizeName(expectedName), action,
        Instant.ofEpochMilli(stored.expiresAt()), authLease(action, stored.payload()));
    if (!this.persistentChallenges.confirm(stored.id(), token, now)) {
      return new Approval(Status.UNKNOWN);
    }
    boolean executed;
    try {
      executed = Boolean.TRUE.equals(executor.apply(challenge));
    } catch (RuntimeException error) {
      executed = false;
    }
    if (!executed) {
      if (!this.persistentChallenges.release(stored.id(), now)) {
        return new Approval(Status.UNKNOWN);
      }
      return new Approval(Status.EXECUTION_FAILED);
    }
    return this.persistentChallenges.consume(stored.id(), now)
        ? new Approval(Status.APPROVED) : new Approval(Status.UNKNOWN);
  }

  private static String kind(Action action) {
    return switch (action) {
      case BIND_EMAIL -> "X_EMAIL";
      case ENABLE_TOTP -> "X_TOTP";
      case BIND_SKIN_ACCOUNT -> "X_SKIN";
      case APPROVE_LOGIN -> "X_LOGIN";
    };
  }

  private static String leasePayload(AuthLease lease) {
    return lease == null ? null : lease.token().toString();
  }

  private static AuthLease authLease(Action action, String payload) {
    if (action != Action.APPROVE_LOGIN) return null;
    try {
      return new AuthLease(UUID.fromString(Objects.requireNonNull(payload, "payload")));
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new IllegalStateException("Login approval has an invalid authentication lease", error);
    }
  }

  private static String normalizeName(String username) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("Player username is required");
    }
    return username.trim().toLowerCase(Locale.ROOT);
  }

  private static String hash(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static TokenGenerator secureTokenGenerator() {
    SecureRandom random = new SecureRandom();
    return () -> {
      byte[] bytes = new byte[TOKEN_BYTES];
      random.nextBytes(bytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    };
  }

  public enum Action {
    BIND_EMAIL,
    ENABLE_TOTP,
    BIND_SKIN_ACCOUNT,
    APPROVE_LOGIN
  }

  public enum Status {
    APPROVED,
    CANCELLED,
    MISMATCH,
    EXPIRED,
    EXECUTION_FAILED,
    UNKNOWN
  }

  public record Challenge(
      String token,
      UUID playerId,
      String username,
      Action action,
      Instant expiresAt,
      AuthLease authLease
  ) { }

  public record Approval(Status status) {
    public boolean success() {
      return this.status == Status.APPROVED;
    }
  }

  @FunctionalInterface
  interface TokenGenerator {
    String next();
  }

  private record Pending(
      UUID playerId,
      String username,
      Action action,
      Instant expiresAt,
      AuthLease authLease) { }
}
