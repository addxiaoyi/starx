/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.repository.UserRepository;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.util.Map;
import java.util.Objects;

public final class LinkExternalUserHandler
implements AdminHandler {
    private static final int MAX_USERNAME_LENGTH = 16;
    private static final int MAX_EXTERNAL_ID_LENGTH = 100;
    private final UserRepository users;
    private final EventBus eventBus;

    public LinkExternalUserHandler(UserRepository users, EventBus eventBus) {
        this.users = Objects.requireNonNull(users, "users");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
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
        UserDto existing = this.users.findByUsername(req.username).orElse(null);
        if (existing == null) {
            ctx.status(404).json(Map.of("error", "User not found"));
            return;
        }
        UserDto updated = UserDto.builder().uuid(existing.uuid()).username(existing.username()).email(existing.email()).premium(existing.premium()).createdAt(existing.createdAt()).lastLoginAt(existing.lastLoginAt()).externalUserId(externalUserId).build();
        this.users.save(updated);
        this.eventBus.publish("link:external-user", Map.of(
            "username", req.username,
            "externalUserId", externalUserId == null ? "" : externalUserId,
            "linked", externalUserId != null));
        ctx.status(200).json(Map.of("success", true, "linked", externalUserId != null));
    }

    static String normalizeExternalUserId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static final class LinkExternalUserRequest {
        public String username;
        public String externalUserId;

        LinkExternalUserRequest() {
        }
    }
}
