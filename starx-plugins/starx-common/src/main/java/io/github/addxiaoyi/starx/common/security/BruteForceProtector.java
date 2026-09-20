/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Clock;
import java.util.Objects;

public final class BruteForceProtector {
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 300000L;
    private static final long RESET_WINDOW_MS = 900000L;
    private static final int DEFAULT_MAX_TRACKED_PLAYERS = 4096;
    private final Map<UUID, FailEntry> attempts = new ConcurrentHashMap<UUID, FailEntry>();
    private final Clock clock;
    private final int maxTrackedPlayers;

    public BruteForceProtector() {
        this(Clock.systemUTC(), DEFAULT_MAX_TRACKED_PLAYERS);
    }

    BruteForceProtector(Clock clock) {
        this(clock, DEFAULT_MAX_TRACKED_PLAYERS);
    }

    BruteForceProtector(Clock clock, int maxTrackedPlayers) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxTrackedPlayers <= 0) {
            throw new IllegalArgumentException("maxTrackedPlayers must be positive");
        }
        this.maxTrackedPlayers = maxTrackedPlayers;
    }

    public Check check(UUID uuid) {
        FailEntry entry = this.attempts.get(uuid);
        if (entry == null) {
            return Check.allowed();
        }
        long now = this.clock.millis();
        if (now - entry.firstFailMs > 900000L) {
            this.attempts.remove(uuid);
            return Check.allowed();
        }
        if (entry.count >= 5) {
            long remainingMs = 300000L - (now - entry.lastFailMs);
            if (remainingMs > 0L) {
                return new Check(BruteForceStatus.LOCKED, remainingMs);
            }
            this.attempts.remove(uuid);
            return Check.allowed();
        }
        long elapsed = now - entry.lastFailMs;
        long delayMs = (1L << entry.count - 1) * 1000L;
        if (elapsed < delayMs) {
            return new Check(BruteForceStatus.DELAYED, delayMs - elapsed);
        }
        return Check.allowed();
    }

    public void recordFailure(UUID uuid) {
        long now = this.clock.millis();
        this.prune(now);
        this.attempts.compute(uuid, (k, entry) -> {
            if (entry == null || now - entry.firstFailMs > 900000L) {
                return new FailEntry(1, now, now);
            }
            return new FailEntry(entry.count + 1, entry.firstFailMs, now);
        });
        this.trim();
    }

    public void clear(UUID uuid) {
        this.attempts.remove(uuid);
    }

    public int getAttemptCount(UUID uuid) {
        FailEntry entry = this.attempts.get(uuid);
        return entry == null ? 0 : entry.count;
    }

    int trackedPlayers() {
        return this.attempts.size();
    }

    private void prune(long now) {
        this.attempts.entrySet().removeIf(
            entry -> now - entry.getValue().firstFailMs > RESET_WINDOW_MS);
    }

    private void trim() {
        while (this.attempts.size() > this.maxTrackedPlayers) {
            this.attempts.entrySet().stream()
                .min(Map.Entry.comparingByValue(
                    (left, right) -> Long.compare(left.lastFailMs, right.lastFailMs)))
                .ifPresent(entry -> this.attempts.remove(entry.getKey(), entry.getValue()));
        }
    }

    private static final class FailEntry {
        final int count;
        final long firstFailMs;
        final long lastFailMs;

        FailEntry(int count, long firstFailMs, long lastFailMs) {
            this.count = count;
            this.firstFailMs = firstFailMs;
            this.lastFailMs = lastFailMs;
        }
    }

    public enum BruteForceStatus {
        ALLOWED,
        DELAYED,
        LOCKED
    }

    public record Check(BruteForceStatus status, long waitMs) {
        public Check {
            Objects.requireNonNull(status, "status");
            if (waitMs < 0) throw new IllegalArgumentException("waitMs must not be negative");
        }

        static Check allowed() {
            return new Check(BruteForceStatus.ALLOWED, 0);
        }
    }
}
