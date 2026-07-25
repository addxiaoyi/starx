/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class UserQueryHandler
implements AdminHandler {
    private final JdbcUserRepository users;

    public UserQueryHandler(JdbcUserRepository users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.get("/v1/user/exists", this.chainWithAuth(this::handleExists, authFilter));
        routes.get("/v1/user/detail", this.chainWithAuth(this::handleDetail, authFilter));
    }

    private RouteRegistrar.RouteHandler chainWithAuth(RouteRegistrar.RouteHandler handler, RouteRegistrar.RouteHandler ... authFilter) {
        return ctx -> {
            for (RouteRegistrar.RouteHandler filter : authFilter) {
                filter.handle(ctx);
            }
            handler.handle(ctx);
        };
    }

    private void handleExists(JsonHttpExchange ctx) throws IOException {
        String name = ctx.queryParam("name");
        if (name == null || name.isBlank()) {
            ctx.status(400).json(Map.of("error", "name is required"));
            return;
        }
        if (name.length() > 16) {
            ctx.status(400).json(Map.of("error", "name too long"));
            return;
        }
        boolean exists = this.users.existsByUsername(name);
        ctx.status(200).json(Map.of("exists", exists));
    }

    private void handleDetail(JsonHttpExchange ctx) throws IOException {
        String name = ctx.queryParam("name");
        if (name == null || name.isBlank()) {
            ctx.status(400).json(Map.of("error", "name is required"));
            return;
        }
        if (name.length() > 16) {
            ctx.status(400).json(Map.of("error", "name too long"));
            return;
        }
        Optional<StarxUser> user = this.users.findFullByUsername(name);
        if (user.isEmpty()) {
            ctx.status(404).json(Map.of("error", "User not found"));
            return;
        }
        StarxUser u = user.get();
        ctx.status(200).json(Map.of("uuid", u.uuid().toString(), "username", u.username(), "registered", true, "createdAt", u.createdAt() != null ? u.createdAt().toString() : Integer.valueOf(0)));
    }
}
