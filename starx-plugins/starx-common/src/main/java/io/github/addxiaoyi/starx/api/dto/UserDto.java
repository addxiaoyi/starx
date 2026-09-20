/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.api.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class UserDto {
    private final UUID uuid;
    private final String username;
    private final String email;
    private final boolean premium;
    private final Instant createdAt;
    private final Instant lastLoginAt;
    private final String externalUserId;

    private UserDto(Builder builder) {
        this.uuid = Objects.requireNonNull(builder.uuid, "uuid");
        this.username = Objects.requireNonNull(builder.username, "username");
        this.email = builder.email;
        this.premium = builder.premium;
        this.createdAt = builder.createdAt;
        this.lastLoginAt = builder.lastLoginAt;
        this.externalUserId = builder.externalUserId;
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

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID uuid;
        private String username;
        private String email;
        private boolean premium;
        private Instant createdAt;
        private Instant lastLoginAt;
        private String externalUserId;

        public Builder uuid(UUID uuid) {
            this.uuid = uuid;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder premium(boolean premium) {
            this.premium = premium;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder lastLoginAt(Instant lastLoginAt) {
            this.lastLoginAt = lastLoginAt;
            return this;
        }

        public Builder externalUserId(String externalUserId) {
            this.externalUserId = externalUserId;
            return this;
        }

        public UserDto build() {
            return new UserDto(this);
        }
    }
}
