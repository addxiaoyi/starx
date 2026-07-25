/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.common.auth.AuthSession;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class SessionManager {
    private static final int DEFAULT_MAX_SESSIONS = 10000;
    private static final int INITIAL_CAPACITY = 1024;
    private static final long CLEANUP_INTERVAL_SECONDS = 30L;
    private final Duration timeout;
    private final Supplier<Instant> clock;
    private final int maxSessions;
    private final Map<UUID, AuthSession> sessions;
    private final ScheduledExecutorService cleanupExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SessionManager(Duration timeout, Supplier<Instant> clock) {
        this(timeout, clock, 10000);
    }

    public SessionManager(Duration timeout, Supplier<Instant> clock, int maxSessions) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive");
        }
        this.maxSessions = maxSessions;
        this.sessions = new ConcurrentHashMap<UUID, AuthSession>(1024);
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "starx-session-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleWithFixedDelay(this::cleanup, 30L, 30L, TimeUnit.SECONDS);
    }

    public synchronized AuthSession open(UUID uuid, String username, InetAddress address, AuthLease lease) {
        return this.open(uuid, username, address, null, lease);
    }

    public synchronized AuthSession open(
        UUID uuid, String username, InetAddress address, String deviceId, AuthLease lease) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(lease, "lease");
        if (this.closed.get()) {
            return null;
        }

        if (!this.sessions.containsKey(uuid) && this.sessions.size() >= this.maxSessions) {
            return null;
        }
        AuthSession session = new AuthSession(
            uuid, username, address, deviceId, lease, this.clock.get());
        this.sessions.put(uuid, session);
        return session;
    }

    public Optional<AuthSession> get(UUID uuid) {
        return Optional.ofNullable(this.sessions.computeIfPresent(uuid, (k, session) -> {
            Instant now = this.clock.get();
            if (session.isExpired(now, this.timeout)) {
                return null;
            }
            return session;
        }));
    }

    public Optional<AuthSession> get(UUID uuid, AuthLease lease) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(lease, "lease");
        AtomicReference<AuthSession> found = new AtomicReference<>();
        this.sessions.computeIfPresent(uuid, (key, session) -> {
            if (session.isExpired(this.clock.get(), this.timeout)) {
                return null;
            }
            if (session.ownedBy(lease)) {
                found.set(session);
            }
            return session;
        });
        return Optional.ofNullable(found.get());
    }

    public void remove(UUID uuid) {
        this.sessions.remove(uuid);
    }

    public boolean remove(UUID uuid, AuthLease lease) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(lease, "lease");
        AtomicBoolean removed = new AtomicBoolean();
        this.sessions.computeIfPresent(uuid, (key, session) -> {
            if (!session.ownedBy(lease)) {
                return session;
            }
            removed.set(true);
            return null;
        });
        return removed.get();
    }

    public boolean removeIfState(
        UUID uuid,
        AuthLease lease,
        AuthSession.State expected
    ) {
        Objects.requireNonNull(expected, "expected");
        AtomicBoolean removed = new AtomicBoolean();
        this.sessions.computeIfPresent(uuid, (key, session) -> {
            if (!session.ownedBy(lease) || session.state() != expected) {
                return session;
            }
            removed.set(true);
            return null;
        });
        return removed.get();
    }

    public boolean transition(
        UUID uuid,
        AuthLease lease,
        AuthSession.State expected,
        AuthSession.State next
    ) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(lease, "lease");
        AtomicBoolean changed = new AtomicBoolean();
        this.sessions.computeIfPresent(uuid, (key, session) -> {
            if (session.ownedBy(lease) && session.transition(expected, next)) {
                session.touch(this.clock.get());
                changed.set(true);
            }
            return session;
        });
        return changed.get();
    }

    public boolean isState(UUID uuid, AuthLease lease, AuthSession.State expected) {
        return this.get(uuid, lease).filter(session -> session.state() == expected).isPresent();
    }

    public int size() {
        return this.sessions.size();
    }

    public void shutdown() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        try {
            this.cleanupExecutor.shutdownNow();
        } finally {
            this.sessions.clear();
        }
    }

    private void cleanup() {
        Instant now = this.clock.get();
        this.sessions.forEach((uuid, snapshot) -> this.sessions.computeIfPresent(
            uuid,
            (key, current) -> current == snapshot && current.isExpired(now, this.timeout)
                ? null
                : current));
    }
}
