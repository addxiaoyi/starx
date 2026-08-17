/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.repository.UserRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import java.util.UUID;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class LinkExternalUserHandler
implements AdminHandler {
    private static final int MAX_USERNAME_LENGTH = 16;
    private static final int MAX_EXTERNAL_ID_LENGTH = 100;
    private final JdbcUserRepository users;
    private final EventBus eventBus;
    private final Function<UUID, UUID> canonicalUuidResolver;
    private final Function<String, java.util.Optional<UserDto>> usernameResolver;

    public LinkExternalUserHandler(JdbcUserRepository users, EventBus eventBus) {
        this(users, eventBus, Function.identity(), users::findByUsername);
    }

    public LinkExternalUserHandler(
        JdbcUserRepository users,
        EventBus eventBus,
        Function<UUID, UUID> canonicalUuidResolver) {
        this(users, eventBus, canonicalUuidResolver, users::findByUsername);
    }

    public LinkExternalUserHandler(
        JdbcUserRepository users,
        EventBus eventBus,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<String, java.util.Optional<UserDto>> usernameResolver) {
        this.users = Objects.requireNonNull(users, "users");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
        this.usernameResolver = Objects.requireNonNull(usernameResolver, "usernameResolver");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.post("/v1/admin/link-external-user", this.chainWithAuth(this::handle, authFilter));
    }

    private RouteRegistrar.RouteHandler chainWithAuth(RouteRegistrar.RouteHandler handler, RouteRegistrar.RouteHandler ... authFilter) {
        return ctx -> {
            for (RouteRegistrar.RouteHandler filter : authFilter) {
                filter.handle(ctx);
            }
            handler.handle(ctx);
        };
    }

    private void handle(JsonHttpExchange ctx) throws Exception {
        LinkExternalUserRequest req = ctx.bodyAsClass(LinkExternalUserRequest.class);
        if (req.username == null || req.username.isBlank()) {
            ctx.status(400).json(Map.of("error", "username is required"));
            return;
        }
        if (req.externalUserId == null) {
            ctx.status(400).json(Map.of("error", "externalUserId is required"));
            return;
        }
        if (req.username.length() > 16) {
            ctx.status(400).json(Map.of("error", "username too long"));
            return;
        }
        String externalUserId = normalizeExternalUserId(req.externalUserId);
        if (externalUserId != null && externalUserId.length() > 100) {
            ctx.status(400).json(Map.of("error", "externalUserId too long (max 100 characters)"));
            return;
        }
        UserDto existing = this.usernameResolver.apply(req.username).orElse(null);
        if (existing == null) {
            ctx.status(404).json(Map.of("error", "User not found"));
            return;
        }
        UUID requestedUuid = parseUuid(req.playerUuid);
        boolean trusted = requestedUuid != null && isTrustedBinding(
            canonicalUuidResolver.apply(existing.uuid()),
            req.username,
            canonicalUuidResolver.apply(requestedUuid).toString(),
            req.username,
            req.verified);
        if (externalUserId != null && req.verified && !trusted) {
            ctx.status(409).json(Map.of("error", "verified identity does not match player"));
            return;
        }
        try {
            this.users.linkExternalIdentity(existing.uuid(), req.username, externalUserId, trusted);
        } catch (JdbcUserRepository.ExternalIdentityConflictException error) {
            ctx.status(409).json(Map.of("error", "external identity already linked"));
            return;
        }
        this.eventBus.publish("link:external-user", Map.of(
            "username", req.username,
            "externalUserId", externalUserId == null ? "" : externalUserId,
            "linked", externalUserId != null,
            "verified", trusted));
        ctx.status(200).json(Map.of(
            "success", true,
            "linked", externalUserId != null,
            "verified", trusted));
    }

    static String normalizeExternalUserId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static boolean isTrustedBinding(UUID existingUuid, String existingUsername, String requestedUuid, String requestedUsername, boolean verified) {
        if (!verified || requestedUuid == null || requestedUsername == null) return false;
        try {
            return existingUuid.equals(UUID.fromString(requestedUuid.trim()))
                && existingUsername.equalsIgnoreCase(requestedUsername.trim());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    static final class LinkExternalUserRequest {
        public String username;
        public String externalUserId;
        public String playerUuid;
        public boolean verified;

        LinkExternalUserRequest() {
        }
    }
}
