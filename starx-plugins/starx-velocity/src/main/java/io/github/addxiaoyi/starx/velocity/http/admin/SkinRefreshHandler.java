/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import io.github.addxiaoyi.starx.velocity.module.skin.SkinBridgeModule;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class SkinRefreshHandler
implements AdminHandler {
    private static final int MAX_USERNAME_LENGTH = 16;
    private final SkinBridgeModule skinBridge;
    private final JdbcUserRepository users;
    private final Function<String, Optional<StarxUser>> usernameResolver;
    private final Function<UUID, UUID> canonicalUuidResolver;

    public SkinRefreshHandler(SkinBridgeModule skinBridge, JdbcUserRepository users) {
        this(skinBridge, users, users::findFullByUsername, Function.identity());
    }

    public SkinRefreshHandler(
        SkinBridgeModule skinBridge,
        JdbcUserRepository users,
        Function<String, Optional<StarxUser>> usernameResolver) {
        this(skinBridge, users, usernameResolver, Function.identity());
    }

    public SkinRefreshHandler(
        SkinBridgeModule skinBridge,
        JdbcUserRepository users,
        Function<String, Optional<StarxUser>> usernameResolver,
        Function<UUID, UUID> canonicalUuidResolver
    ) {
        this.skinBridge = Objects.requireNonNull(skinBridge, "skinBridge");
        this.users = Objects.requireNonNull(users, "users");
        this.usernameResolver = Objects.requireNonNull(usernameResolver, "usernameResolver");
        this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.post("/v1/admin/skin-refresh", this.chainWithAuth(this::handle, authFilter));
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
        SkinRefreshRequest req = ctx.bodyAsClass(SkinRefreshRequest.class);
        String username = req.username == null ? "" : req.username.trim();
        UUID requestedUuid = null;
        if (req.player_uuid != null && !req.player_uuid.isBlank()) {
            try {
                requestedUuid = UUID.fromString(req.player_uuid.replaceFirst(
                    "^(.{8})(.{4})(.{4})(.{4})(.{12})$", "$1-$2-$3-$4-$5"));
            } catch (IllegalArgumentException error) {
                ctx.status(400).json(Map.of("error", "player_uuid is invalid"));
                return;
            }
        }
        if (username.isBlank() && requestedUuid == null) {
            ctx.status(400).json(Map.of("error", "username or player_uuid is required"));
            return;
        }
        if (username.length() > MAX_USERNAME_LENGTH) {
            ctx.status(400).json(Map.of("error", "username too long"));
            return;
        }

        UUID uuid = requestedUuid;
        if (uuid != null) {
            uuid = Objects.requireNonNull(this.canonicalUuidResolver.apply(uuid), "canonicalUuidResolver returned null");
        } else {
            Optional<StarxUser> user = this.usernameResolver.apply(username);
            if (user.isEmpty()) {
                ctx.status(404).json(Map.of("error", "User not found"));
                return;
            }
            uuid = user.get().uuid();
        }
        String playerName = username.isBlank()
            ? this.users.findByUuid(uuid).map(user -> user.username()).orElse(uuid.toString())
            : username;
        boolean accepted = this.skinBridge.refreshSkinFromWebsite(uuid, playerName);
        ctx.status(accepted ? 202 : 200).json(Map.of(
            "success", true,
            "accepted", accepted,
            "player_uuid", uuid.toString()));
    }

    static final class SkinRefreshRequest {
        public String username;
        public String player_uuid;
        public String reason;

        SkinRefreshRequest() {
        }
    }
}
