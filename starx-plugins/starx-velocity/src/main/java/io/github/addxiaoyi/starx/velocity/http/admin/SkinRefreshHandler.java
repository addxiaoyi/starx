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

public final class SkinRefreshHandler
implements AdminHandler {
    private static final int MAX_USERNAME_LENGTH = 16;
    private final SkinBridgeModule skinBridge;
    private final JdbcUserRepository users;

    public SkinRefreshHandler(SkinBridgeModule skinBridge, JdbcUserRepository users) {
        this.skinBridge = Objects.requireNonNull(skinBridge, "skinBridge");
        this.users = Objects.requireNonNull(users, "users");
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
        if (req.username == null || req.username.isBlank()) {
            ctx.status(400).json(Map.of("error", "username is required"));
            return;
        }
        if (req.username.length() > 16) {
            ctx.status(400).json(Map.of("error", "username too long"));
            return;
        }
        Optional<StarxUser> user = this.users.findFullByUsername(req.username);
        if (user.isEmpty()) {
            ctx.status(404).json(Map.of("error", "User not found"));
            return;
        }
        this.skinBridge.refreshSkin(user.get().uuid(), user.get().username());
        ctx.status(200).json(Map.of("success", true));
    }

    static final class SkinRefreshRequest {
        public String username;

        SkinRefreshRequest() {
        }
    }
}
