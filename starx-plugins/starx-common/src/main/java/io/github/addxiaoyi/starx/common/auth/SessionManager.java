/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.common.auth.AuthSession;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Supplier;

public final class SessionManager {
    private static final Logger logger = Logger.getLogger(SessionManager.class.getName());
    private static final int DEFAULT_MAX_SESSIONS = 10000;
    private static final int INITIAL_CAPACITY = 1024;
    private static final long CLEANUP_INTERVAL_SECONDS = 30L;
    private final Duration timeout;
    private final Supplier<Instant> clock;
    private final int maxSessions;
    private final Map<UUID, AuthSession> sessions;
    private final CopyOnWriteArrayList<Consumer<AuthSession>> expirationListeners =
        new CopyOnWriteArrayList<>();
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
        AtomicReference<AuthSession> expired = new AtomicReference<>();
        AuthSession current = this.sessions.computeIfPresent(uuid, (k, session) -> {
            Instant now = this.clock.get();
            if (session.isExpired(now, this.timeout)) {
                expired.set(session);
                return null;
            }
            return session;
        });
        this.notifyExpired(expired.get());
        return Optional.ofNullable(current);
    }

    public Optional<AuthSession> get(UUID uuid, AuthLease lease) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(lease, "lease");
        AtomicReference<AuthSession> found = new AtomicReference<>();
        AtomicReference<AuthSession> expired = new AtomicReference<>();
        this.sessions.computeIfPresent(uuid, (key, session) -> {
            if (session.isExpired(this.clock.get(), this.timeout)) {
                expired.set(session);
                return null;
            }
            if (session.ownedBy(lease)) {
                found.set(session);
            }
            return session;
        });
        this.notifyExpired(expired.get());
        return Optional.ofNullable(found.get());
    }

    public void addExpirationListener(Consumer<AuthSession> listener) {
        this.expirationListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void remove(UUID uuid) {
        this.sessions.remove(uuid);
    }

    public List<UUID> sessionIdsForUsername(String username) {
        if (username == null || username.isBlank()) {
            return List.of();
        }
        return this.sessions.entrySet().stream()
            .filter(entry -> entry.getValue().username().equalsIgnoreCase(username))
            .map(Map.Entry::getKey)
            .toList();
    }

    public List<UUID> sessionIdsForUuids(Collection<UUID> uuids) {
        Objects.requireNonNull(uuids, "uuids");
        if (uuids.isEmpty()) return List.of();
        java.util.Set<UUID> known = new java.util.HashSet<>(uuids);
        return this.sessions.keySet().stream()
            .filter(known::contains)
            .toList();
    }

    public void removeByUsername(String username) {
        this.sessionIdsForUsername(username).forEach(this.sessions::remove);
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
        AtomicReference<AuthSession> expired = new AtomicReference<>();
        this.sessions.computeIfPresent(uuid, (key, session) -> {
            if (session.isExpired(this.clock.get(), this.timeout)) {
                expired.set(session);
                return null;
            }
            if (session.ownedBy(lease) && session.transition(expected, next)) {
                session.touch(this.clock.get());
                changed.set(true);
            }
            return session;
        });
        this.notifyExpired(expired.get());
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
            List<AuthSession> closing = List.copyOf(this.sessions.values());
            this.sessions.clear();
            closing.forEach(this::notifyExpired);
        }
    }

    private void cleanup() {
        Instant now = this.clock.get();
        this.sessions.forEach((uuid, snapshot) -> {
            AtomicReference<AuthSession> expired = new AtomicReference<>();
            this.sessions.computeIfPresent(
                uuid,
                (key, current) -> {
                    if (current == snapshot && current.isExpired(now, this.timeout)) {
                        expired.set(current);
                        return null;
                    }
                    return current;
                });
            this.notifyExpired(expired.get());
        });
    }

    private void notifyExpired(AuthSession expired) {
        if (expired == null) return;
        for (Consumer<AuthSession> listener : this.expirationListeners) {
            try {
                listener.accept(expired);
            } catch (RuntimeException error) {
                logger.log(Level.WARNING, "Session expiration listener failed", error);
            }
        }
    }
}
