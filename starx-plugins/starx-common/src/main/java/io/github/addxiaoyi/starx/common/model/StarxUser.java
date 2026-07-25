/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class StarxUser {
    private final UUID uuid;
    private final String username;
    private final String email;
    private final String passwordHash;
    private final String totpSecret;
    private final boolean premium;
    private final Instant createdAt;
    private final Instant lastLoginAt;
    private final String externalUserId;
    private final List<String> trustedDevices;
    private final String recoveryCodes;
    private final String sourceSystem;
    private final String migrationState;
    private final Instant passwordMigratedAt;
    private final String lastLoginIp;
    private final String lastLoginIsp;
    private final String lastLoginLocation;
    private final Long totalPlaytime;
    private final Instant lastLogoutAt;
    private final Boolean welcomeMessageShown;

    public StarxUser(UUID uuid, String username, String email, String passwordHash, String totpSecret, boolean premium, Instant createdAt, Instant lastLoginAt, String externalUserId, List<String> trustedDevices, String recoveryCodes, String sourceSystem, String migrationState, Instant passwordMigratedAt, String lastLoginIp, String lastLoginIsp, String lastLoginLocation, Long totalPlaytime, Instant lastLogoutAt, Boolean welcomeMessageShown) {
        this.uuid = uuid;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.totpSecret = totpSecret;
        this.premium = premium;
        this.createdAt = createdAt;
        this.lastLoginAt = lastLoginAt;
        this.externalUserId = externalUserId;
        this.trustedDevices = trustedDevices == null ? List.of() : Collections.unmodifiableList(new ArrayList<String>(trustedDevices));
        this.recoveryCodes = recoveryCodes;
        this.sourceSystem = sourceSystem;
        this.migrationState = migrationState;
        this.passwordMigratedAt = passwordMigratedAt;
        this.lastLoginIp = lastLoginIp;
        this.lastLoginIsp = lastLoginIsp;
        this.lastLoginLocation = lastLoginLocation;
        this.totalPlaytime = totalPlaytime;
        this.lastLogoutAt = lastLogoutAt;
        this.welcomeMessageShown = welcomeMessageShown;
    }

    public UUID uuid() {
        return this.uuid;
    }

    public String username() {
        return this.username;
    }

    public String email() {
        return this.email;
    }

    public String passwordHash() {
        return this.passwordHash;
    }

    public String totpSecret() {
        return this.totpSecret;
    }

    public boolean premium() {
        return this.premium;
    }

    public Instant createdAt() {
        return this.createdAt;
    }

    public Instant lastLoginAt() {
        return this.lastLoginAt;
    }

    public String externalUserId() {
        return this.externalUserId;
    }

    public List<String> trustedDevices() {
        return this.trustedDevices;
    }

    public String recoveryCodes() {
        return this.recoveryCodes;
    }

    public String sourceSystem() {
        return this.sourceSystem;
    }

    public String migrationState() {
        return this.migrationState;
    }

    public Instant passwordMigratedAt() {
        return this.passwordMigratedAt;
    }

    public String lastLoginIp() {
        return this.lastLoginIp;
    }

    public String lastLoginIsp() {
        return this.lastLoginIsp;
    }

    public String lastLoginLocation() {
        return this.lastLoginLocation;
    }

    public Long totalPlaytime() {
        return this.totalPlaytime;
    }

    public Instant lastLogoutAt() {
        return this.lastLogoutAt;
    }

    public Boolean welcomeMessageShown() {
        return this.welcomeMessageShown;
    }
}
