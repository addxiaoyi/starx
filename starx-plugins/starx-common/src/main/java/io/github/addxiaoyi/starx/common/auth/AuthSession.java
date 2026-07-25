/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class AuthSession {
    private final UUID uuid;
    private final String username;
    private final InetAddress address;
    private final String deviceId;
    private final AuthLease lease;
    private final AtomicReference<State> state;
    private final Instant createdAt;
    private final AtomicReference<Instant> lastActivityAt;

    public AuthSession(
        UUID uuid, String username, InetAddress address, String deviceId,
        AuthLease lease, Instant createdAt) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.username = Objects.requireNonNull(username, "username");
        this.address = address;
        this.deviceId = deviceId;
        this.lease = Objects.requireNonNull(lease, "lease");
        this.state = new AtomicReference<>(State.GUEST);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastActivityAt = new AtomicReference<>(createdAt);
    }

    public UUID uuid() {
        return this.uuid;
    }

    public String username() {
        return this.username;
    }

    public InetAddress address() {
        return this.address;
    }

    public String deviceId() {
        return this.deviceId;
    }

    public AuthLease lease() {
        return this.lease;
    }

    public boolean ownedBy(AuthLease expected) {
        return this.lease.equals(expected);
    }

    public State state() {
        return this.state.get();
    }

    boolean transition(State expected, State next) {
        return this.state.compareAndSet(
            Objects.requireNonNull(expected, "expected"),
            Objects.requireNonNull(next, "next"));
    }

    public Instant createdAt() {
        return this.createdAt;
    }

    public Instant lastActivityAt() {
        return this.lastActivityAt.get();
    }

    public void touch(Instant now) {
        this.lastActivityAt.set(Objects.requireNonNull(now, "now"));
    }

    public boolean isExpired(Instant now, Duration timeout) {
        return now.isAfter(this.lastActivityAt.get().plus(timeout));
    }

    public static enum State {
        GUEST,
        AUTHENTICATING,
        WEB_APPROVAL_PENDING,
        AUTHENTICATED;

    }
}
