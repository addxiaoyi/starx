/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.common.binding.BindingChallenge;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Function;

public final class BindingVerificationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long CODE_TTL_MS = 300000L;
    private final ConcurrentMap<String, PendingCode> pendingCodes = new ConcurrentHashMap<String, PendingCode>();
    private final BindingChallengeService persistentChallenges;
    private final Function<UUID, String> accountByPlayer;
    private final Function<String, UUID> playerByAccount;
    private final Clock clock;
    private final Duration lifetime;

    public BindingVerificationService() {
        this.persistentChallenges = null;
        this.accountByPlayer = null;
        this.playerByAccount = null;
        this.clock = Clock.systemUTC();
        this.lifetime = Duration.ofMillis(CODE_TTL_MS);
    }

    public BindingVerificationService(
            BindingChallengeService persistentChallenges,
            Function<UUID, String> accountByPlayer,
            Function<String, UUID> playerByAccount,
            Clock clock,
            Duration lifetime) {
        this.persistentChallenges = Objects.requireNonNull(persistentChallenges, "persistentChallenges");
        this.accountByPlayer = Objects.requireNonNull(accountByPlayer, "accountByPlayer");
        this.playerByAccount = Objects.requireNonNull(playerByAccount, "playerByAccount");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("lifetime must be positive");
        }
    }

    public String generateCode(UUID playerUuid) {
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        if (this.persistentChallenges != null) {
            String accountId = this.accountByPlayer.apply(Objects.requireNonNull(playerUuid, "playerUuid"));
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("Player account identity is not registered");
            }
            this.persistentChallenges.begin(accountId, "QQ", code, this.clock.instant(), this.lifetime);
            return code;
        }
        this.pendingCodes.put(code, new PendingCode(
            playerUuid, this.clock.millis() + this.lifetime.toMillis()));
        return code;
    }

    public UUID verifyCode(String code) {
        return this.verifyCodeIf(code, ignored -> true);
    }

    public UUID verifyCodeIf(String code, Predicate<UUID> acceptance) {
        if (code == null || acceptance == null) {
            return null;
        }
        if (this.persistentChallenges != null) {
            Instant now = this.clock.instant();
            BindingChallenge challenge = this.persistentChallenges.inspect("QQ", code, now);
            if (challenge == null) return null;
            UUID playerId = this.playerByAccount.apply(challenge.accountId());
            if (playerId == null || !acceptance.test(playerId)) return null;
            if (!this.persistentChallenges.confirm(challenge.id(), code, now)) return null;
            return this.persistentChallenges.consume(challenge.id(), now) ? playerId : null;
        }
        AtomicReference<UUID> verified = new AtomicReference<>();
        this.pendingCodes.computeIfPresent(code, (ignored, pending) -> {
            if (this.clock.millis() > pending.expiresAt) {
                return null;
            }
            if (!acceptance.test(pending.playerUuid)) {
                return pending;
            }
            verified.set(pending.playerUuid);
            return null;
        });
        return verified.get();
    }

    public UUID verifyAndExecute(String code, Predicate<UUID> action) {
        if (code == null || action == null) return null;
        if (this.persistentChallenges == null) {
            AtomicReference<UUID> verified = new AtomicReference<>();
            this.pendingCodes.computeIfPresent(code, (ignored, pending) -> {
                if (this.clock.millis() > pending.expiresAt) return null;
                if (!action.test(pending.playerUuid)) return pending;
                verified.set(pending.playerUuid);
                return null;
            });
            return verified.get();
        }
        Instant now = this.clock.instant();
        BindingChallenge challenge = this.persistentChallenges.inspect("QQ", code, now);
        if (challenge == null) return null;
        UUID playerId = this.playerByAccount.apply(challenge.accountId());
        if (playerId == null || !this.persistentChallenges.confirm(challenge.id(), code, now)) {
            return null;
        }
        boolean executed;
        try {
            executed = action.test(playerId);
        } catch (RuntimeException error) {
            this.persistentChallenges.release(challenge.id(), now);
            throw error;
        }
        if (!executed) {
            this.persistentChallenges.release(challenge.id(), now);
            return null;
        }
        return this.persistentChallenges.consume(challenge.id(), now) ? playerId : null;
    }

    private record PendingCode(UUID playerUuid, long expiresAt) {
    }
}
