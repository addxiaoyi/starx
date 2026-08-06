package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.common.binding.BindingChallenge;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeAction;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class EmailChallengeService {
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int EMAIL_CODE_BOUND = 1_000_000;
  private static final String EMAIL_CODE_FORMAT = "%06d";
  private static final int MAX_ATTEMPTS = 6;

  private final EmailSender sender;
  private final Duration ttl;
  private final Map<UUID, Challenge> challenges = new ConcurrentHashMap<>();
  private final BindingChallengeService persistentChallenges;
  private final Function<UUID, String> accountByPlayer;
  private final Clock clock;

  public EmailChallengeService(EmailSender sender, Duration ttl) {
    this(sender, ttl, null, null, Clock.systemUTC());
  }

  public EmailChallengeService(
      EmailSender sender,
      Duration ttl,
      BindingChallengeService persistentChallenges,
      Function<UUID, String> accountByPlayer,
      Clock clock) {
    this.sender = Objects.requireNonNull(sender, "sender");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    this.persistentChallenges = persistentChallenges;
    this.accountByPlayer = accountByPlayer;
    this.clock = Objects.requireNonNull(clock, "clock");
    if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("ttl must be positive");
    if (persistentChallenges != null) Objects.requireNonNull(accountByPlayer, "accountByPlayer");
  }

  public void begin(UUID playerId, String rawEmail) {
    Objects.requireNonNull(playerId, "playerId");
    String email = Objects.requireNonNullElse(rawEmail, "").trim().toLowerCase(Locale.ROOT);
    if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
      throw new IllegalArgumentException("请输入有效的邮箱地址");
    }
    String code = EMAIL_CODE_FORMAT.formatted(RANDOM.nextInt(EMAIL_CODE_BOUND));
    if (this.persistentChallenges != null) {
      String accountId = this.accountByPlayer.apply(playerId);
      if (accountId == null || accountId.isBlank()) {
        throw new IllegalArgumentException("Player account identity is not registered");
      }
      Instant now = this.clock.instant();
      String id = this.persistentChallenges.begin(
          accountId, "EMAIL", email, code, now, this.ttl);
      try {
        this.sender.sendVerificationCode(email, code);
      } catch (RuntimeException error) {
        this.persistentChallenges.revoke(id, now);
        throw error;
      }
      return;
    }
    this.sender.sendVerificationCode(email, code);
    this.challenges.put(playerId, new Challenge(
        UUID.randomUUID().toString(), email, hash(playerId, code),
        this.clock.instant().plus(ttl), 0));
  }

  public String confirm(UUID playerId, String rawCode) {
    String code = Objects.requireNonNullElse(rawCode, "").trim();
    if (this.persistentChallenges != null) {
      Instant now = this.clock.instant();
      BindingChallenge challenge = this.persistentChallenges.inspectExecutable("EMAIL", code, now);
      if (challenge == null) throw new IllegalStateException("邮箱验证码无效或已过期");
      String accountId = this.accountByPlayer.apply(Objects.requireNonNull(playerId, "playerId"));
      if (!challenge.accountId().equals(accountId)) {
        throw new IllegalArgumentException("邮箱验证码与玩家不匹配");
      }
      BindingChallengeService.Execution execution =
          this.persistentChallenges.acquire(challenge, now);
      if (execution == null || !this.persistentChallenges.consume(execution, now)) {
        throw new IllegalStateException("邮箱验证码已被使用");
      }
      return challenge.payload();
    }
    AtomicReference<String> confirmedEmail = new AtomicReference<>();
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    Instant now = this.clock.instant();
    this.challenges.compute(playerId, (id, challenge) -> {
      if (challenge == null) {
        failure.set(new IllegalStateException("请先获取邮箱验证码"));
        return null;
      }
      if (!challenge.expiresAt().isAfter(now)) {
        failure.set(new IllegalStateException("邮箱验证码已过期"));
        return null;
      }
      int attempts = challenge.attempts() + 1;
      if (attempts > MAX_ATTEMPTS) {
        failure.set(new IllegalStateException("尝试次数过多，请重新获取验证码"));
        return null;
      }
      if (!MessageDigest.isEqual(
          challenge.codeHash().getBytes(StandardCharsets.US_ASCII),
          hash(playerId, code).getBytes(StandardCharsets.US_ASCII))) {
        failure.set(new IllegalArgumentException("邮箱验证码错误"));
        return challenge.withAttempts(attempts);
      }
      confirmedEmail.set(challenge.email());
      return null;
    });
    RuntimeException error = failure.get();
    if (error != null) throw error;
    return confirmedEmail.get();
  }

  public boolean confirmAndExecute(
      UUID playerId, String rawCode, Function<String, Boolean> executor) {
    Objects.requireNonNull(executor, "executor");
    return confirmAndExecute(
        playerId, rawCode, (ignoredOperationId, email) -> executor.apply(email));
  }

  public boolean confirmAndExecute(
      UUID playerId, String rawCode, BindingChallengeAction<String> executor) {
    Objects.requireNonNull(executor, "executor");
    if (this.persistentChallenges == null) {
      String code = Objects.requireNonNullElse(rawCode, "").trim();
      AtomicReference<Boolean> executed = new AtomicReference<>(false);
      AtomicReference<RuntimeException> failure = new AtomicReference<>();
      Instant now = this.clock.instant();
      this.challenges.compute(Objects.requireNonNull(playerId, "playerId"), (id, challenge) -> {
        if (challenge == null) {
          failure.set(new IllegalStateException("请先获取邮箱验证码"));
          return null;
        }
        if (!challenge.expiresAt().isAfter(now)) {
          failure.set(new IllegalStateException("邮箱验证码已过期"));
          return null;
        }
        int attempts = challenge.attempts() + 1;
        if (attempts > MAX_ATTEMPTS) {
          failure.set(new IllegalStateException("尝试次数过多，请重新获取验证码"));
          return null;
        }
        if (!MessageDigest.isEqual(
            challenge.codeHash().getBytes(StandardCharsets.US_ASCII),
            hash(playerId, code).getBytes(StandardCharsets.US_ASCII))) {
          failure.set(new IllegalArgumentException("邮箱验证码错误"));
          return challenge.withAttempts(attempts);
        }
        try {
          executed.set(executor.execute(challenge.operationId(), challenge.email()));
        } catch (RuntimeException ignored) {
          executed.set(false);
        }
        return executed.get() ? null : challenge;
      });
      RuntimeException error = failure.get();
      if (error != null) throw error;
      return executed.get();
    }

    String code = Objects.requireNonNullElse(rawCode, "").trim();
    Instant now = this.clock.instant();
    BindingChallenge challenge = this.persistentChallenges.inspectExecutable("EMAIL", code, now);
    if (challenge == null) return false;
    String accountId = this.accountByPlayer.apply(Objects.requireNonNull(playerId, "playerId"));
    if (!challenge.accountId().equals(accountId)) return false;
    BindingChallengeService.Execution execution =
        this.persistentChallenges.acquire(challenge, now);
    if (execution == null) return false;

    boolean executed;
    try {
      executed = executor.execute(execution.operationId(), challenge.payload());
    } catch (RuntimeException error) {
      executed = false;
    }
    if (!executed) {
      this.persistentChallenges.release(execution, now);
      return false;
    }
    return this.persistentChallenges.consume(execution, now);
  }

  private static String hash(UUID playerId, String code) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(
          (playerId + ":" + code).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private record Challenge(
      String operationId,
      String email,
      String codeHash,
      Instant expiresAt,
      int attempts) {
    private Challenge withAttempts(int value) {
      return new Challenge(this.operationId, this.email, this.codeHash, this.expiresAt, value);
    }
  }
}
