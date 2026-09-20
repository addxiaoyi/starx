package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.common.binding.BindingChallenge;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeAction;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeService;
import io.github.addxiaoyi.starx.common.binding.JdbcBindingChallengeRepository.ChallengeTokenConflictException;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class CrossDeviceApprovalService {
  private static final int TOKEN_BYTES = 32;
  private static final int MAX_TOKEN_ALLOCATION_ATTEMPTS = 8;

  private final Clock clock;
  private final Duration ttl;
  private final TokenGenerator tokens;
  private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
  private final BindingChallengeService persistentChallenges;
  private final BiFunction<UUID, String, String> accountByPlayer;
  private final Function<String, UUID> playerByAccount;
  private final Function<String, String> nameByAccount;
  private volatile Function<UUID, Set<UUID>> knownMinecraftUuidsResolver = uuid -> Set.of(uuid);

  public CrossDeviceApprovalService() {
    this(Clock.systemUTC(), Duration.ofMinutes(5), secureTokenGenerator());
  }

  public CrossDeviceApprovalService(
      BindingChallengeService persistentChallenges,
      Function<UUID, String> accountByPlayer,
      Function<String, UUID> playerByAccount,
      Function<String, String> nameByAccount) {
    this(Clock.systemUTC(), Duration.ofMinutes(5), secureTokenGenerator(),
        persistentChallenges,
        (playerId, ignoredUsername) -> accountByPlayer.apply(playerId),
        playerByAccount, nameByAccount);
  }

  public CrossDeviceApprovalService(
      BindingChallengeService persistentChallenges,
      BiFunction<UUID, String, String> accountByPlayer,
      Function<String, UUID> playerByAccount,
      Function<String, String> nameByAccount) {
    this(Clock.systemUTC(), Duration.ofMinutes(5), secureTokenGenerator(),
        persistentChallenges, accountByPlayer, playerByAccount, nameByAccount);
  }

  public void bindKnownMinecraftUuidsResolver(Function<UUID, Set<UUID>> resolver) {
    this.knownMinecraftUuidsResolver = Objects.requireNonNull(resolver, "resolver");
  }

  CrossDeviceApprovalService(Clock clock, Duration ttl, TokenGenerator tokens) {
    this(clock, ttl, tokens, null,
        (BiFunction<UUID, String, String>) null, null, null);
  }

  public CrossDeviceApprovalService(
      Clock clock,
      Duration ttl,
      TokenGenerator tokens,
      BindingChallengeService persistentChallenges,
      Function<UUID, String> accountByPlayer,
      Function<String, UUID> playerByAccount,
      Function<String, String> nameByAccount) {
    this(clock, ttl, tokens, persistentChallenges,
        (playerId, ignoredUsername) -> accountByPlayer.apply(playerId),
        playerByAccount, nameByAccount);
  }

  CrossDeviceApprovalService(
      Clock clock,
      Duration ttl,
      TokenGenerator tokens,
      BindingChallengeService persistentChallenges,
      BiFunction<UUID, String, String> accountByPlayer,
      Function<String, UUID> playerByAccount,
      Function<String, String> nameByAccount) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.persistentChallenges = persistentChallenges;
    this.accountByPlayer = accountByPlayer;
    this.playerByAccount = playerByAccount;
    this.nameByAccount = nameByAccount;
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Approval TTL must be positive");
    }
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
    return this.create(playerId, username, action, null, null);
  }

  public Challenge create(UUID playerId, String username, Action action, String payload) {
    if (action == Action.APPROVE_LOGIN) {
      throw new IllegalArgumentException("Login approval requires an authentication lease");
    }
    return this.create(playerId, username, action, null, payload);
  }

  public Challenge createLogin(UUID playerId, String username, AuthLease lease) {
    return this.create(
        playerId, username, Action.APPROVE_LOGIN,
        Objects.requireNonNull(lease, "lease"), null);
  }

  private Challenge create(
      UUID playerId, String username, Action action, AuthLease authLease) {
    return this.create(playerId, username, action, authLease, null);
  }

  private Challenge create(
      UUID playerId, String username, Action action, AuthLease authLease, String payload) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(action, "action");
    String playerName = normalizeName(username);
    Instant expiresAt = this.clock.instant().plus(this.ttl);
    if (this.persistentChallenges != null) {
      String accountId = this.accountByPlayer.apply(playerId, playerName);
      if (accountId == null || accountId.isBlank()) {
        throw new IllegalArgumentException("Player account identity is not registered");
      }
      return createPersistent(
          accountId, playerId, playerName, action, authLease, payload, expiresAt);
    }
    return createMemory(playerId, playerName, action, authLease, payload, expiresAt);
  }

  private Challenge createPersistent(
      String accountId,
      UUID playerId,
      String playerName,
      Action action,
      AuthLease authLease,
      String payload,
      Instant expiresAt) {
    ChallengeTokenConflictException conflict = null;
    for (int attempt = 0; attempt < MAX_TOKEN_ALLOCATION_ATTEMPTS; attempt++) {
      String token = nextToken();
      try {
        this.persistentChallenges.begin(
            accountId, kind(action), challengePayload(action, authLease, payload), token,
            this.clock.instant(), this.ttl);
        return new Challenge(token, playerId, playerName, action, expiresAt, authLease, payload);
      } catch (ChallengeTokenConflictException error) {
        conflict = error;
      }
    }
    throw new IllegalStateException("Unable to allocate a unique approval token", conflict);
  }

  private Challenge createMemory(
      UUID playerId,
      String playerName,
      Action action,
      AuthLease authLease,
      String payload,
      Instant expiresAt) {
    for (int attempt = 0; attempt < MAX_TOKEN_ALLOCATION_ATTEMPTS; attempt++) {
      String token = nextToken();
      Pending challenge = new Pending(
          UUID.randomUUID().toString(), playerId, playerName, action, expiresAt, authLease, payload);
      if (this.pending.putIfAbsent(hash(token), challenge) == null) {
        return new Challenge(token, playerId, playerName, action, expiresAt, authLease, payload);
      }
    }
    throw new IllegalStateException("Unable to allocate a unique approval token");
  }

  private String nextToken() {
    String token = Objects.requireNonNull(this.tokens.next(), "token").trim();
    if (token.length() < 32) {
      throw new IllegalStateException("Approval token has insufficient entropy");
    }
    return token;
  }

  public Approval approve(String token, UUID playerId, String username, Action action) {
    return this.approveAndExecute(token, playerId, username, action, ignored -> true);
  }

  public Approval approveAndExecute(
      String token,
      UUID playerId,
      String username,
      Action action,
      Function<Challenge, Boolean> executor) {
    Objects.requireNonNull(executor, "executor");
    return approveAndExecute(
        token, playerId, username, action,
        (ignoredOperationId, challenge) -> Boolean.TRUE.equals(executor.apply(challenge)));
  }

  public Approval approveAndExecute(
      String token,
      UUID playerId,
      String username,
      Action action,
      BindingChallengeAction<Challenge> executor) {
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
    boolean matches = sameMinecraftIdentity(challenge.playerId(), playerId)
        && challenge.username().equals(normalizeName(username))
        && challenge.action() == action;
    if (!matches) return new Approval(Status.MISMATCH);

    synchronized (challenge) {
      if (this.pending.get(tokenHash) != challenge) {
        return new Approval(Status.UNKNOWN);
      }
      if (!challenge.expiresAt().isAfter(this.clock.instant())) {
        this.pending.remove(tokenHash, challenge);
        return new Approval(Status.EXPIRED);
      }
      boolean executed;
      try {
        executed = executor.execute(challenge.operationId(), new Challenge(
            token.trim(), challenge.playerId(), challenge.username(), challenge.action(),
            challenge.expiresAt(), challenge.authLease(), challenge.payload()));
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
        String expectedName = this.nameByAccount.apply(challenge.accountId());
        if (!sameAccount(challenge.accountId(), playerId)
            || !normalizeName(username).equals(normalizeName(expectedName))) {
          return new Approval(Status.MISMATCH);
        }
        return this.persistentChallenges.revoke(challenge.id(), this.clock.instant())
            ? new Approval(Status.CANCELLED)
            : new Approval(Status.UNKNOWN);
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
    boolean matches = sameMinecraftIdentity(challenge.playerId(), playerId)
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
      BindingChallengeAction<Challenge> executor) {
    Instant now = this.clock.instant();
    BindingChallenge stored =
        this.persistentChallenges.inspectExecutable(kind(action), token, now);
    if (stored == null) return new Approval(Status.UNKNOWN);
    String expectedName = this.nameByAccount.apply(stored.accountId());
    if (!sameAccount(stored.accountId(), playerId)
        || !normalizeName(username).equals(normalizeName(expectedName))) {
      return new Approval(Status.MISMATCH);
    }

    BindingChallengeService.Execution execution =
        this.persistentChallenges.acquire(stored, now);
    if (execution == null) return new Approval(Status.UNKNOWN);
    UUID resolvedPlayer = this.playerByAccount.apply(stored.accountId());
    if (resolvedPlayer == null) {
      this.persistentChallenges.release(execution, this.clock.instant());
      return new Approval(Status.UNKNOWN);
    }
    Challenge challenge = new Challenge(
        token, resolvedPlayer, normalizeName(expectedName), action,
        Instant.ofEpochMilli(stored.expiresAt()), authLease(action, stored.payload()),
        action == Action.APPROVE_LOGIN ? null : stored.payload());

    boolean executed;
    try {
      executed = executor.execute(execution.operationId(), challenge);
    } catch (RuntimeException error) {
      executed = false;
    }
    Instant completedAt = this.clock.instant();
    if (!executed) {
      return this.persistentChallenges.release(execution, completedAt)
          ? new Approval(Status.EXECUTION_FAILED)
          : new Approval(Status.UNKNOWN);
    }
    return this.persistentChallenges.consume(execution, completedAt)
        ? new Approval(Status.APPROVED)
        : new Approval(Status.UNKNOWN);
  }

  private static String kind(Action action) {
    return switch (action) {
      case BIND_EMAIL -> "X_EMAIL";
      case ENABLE_TOTP -> "X_TOTP";
      case BIND_SKIN_ACCOUNT -> "X_SKIN";
      case APPROVE_LOGIN -> "X_LOGIN";
    };
  }

  private boolean sameAccount(String accountId, UUID playerId) {
    return Objects.equals(accountId, this.accountByPlayer.apply(playerId, null));
  }

  private boolean sameMinecraftIdentity(UUID expectedPlayer, UUID requestedPlayer) {
    if (expectedPlayer.equals(requestedPlayer)) return true;
    Set<UUID> known = this.knownMinecraftUuidsResolver.apply(requestedPlayer);
    return known != null && known.contains(expectedPlayer);
  }

  private static String leasePayload(AuthLease lease) {
    return lease == null ? null : lease.token().toString();
  }

  private static String challengePayload(Action action, AuthLease lease, String payload) {
    return action == Action.APPROVE_LOGIN ? leasePayload(lease) : payload;
  }

  private static AuthLease authLease(Action action, String payload) {
    if (action != Action.APPROVE_LOGIN) return null;
    try {
      return new AuthLease(UUID.fromString(Objects.requireNonNull(payload, "payload")));
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new IllegalStateException(
          "Login approval has an invalid authentication lease", error);
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
      AuthLease authLease,
      String payload) {
    public Challenge(
        String token, UUID playerId, String username, Action action, Instant expiresAt,
        AuthLease authLease) {
      this(token, playerId, username, action, expiresAt, authLease, null);
    }
  }

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
      String operationId,
      UUID playerId,
      String username,
      Action action,
      Instant expiresAt,
      AuthLease authLease,
      String payload) {}
}
