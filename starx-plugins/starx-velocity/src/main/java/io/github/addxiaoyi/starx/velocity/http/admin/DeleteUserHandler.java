/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.auth.AuthResult;
import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.common.account.JdbcAccountErasureRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;

public final class DeleteUserHandler
implements AdminHandler {
    private final AuthService authService;
    private final JdbcUserRepository users;
    private final JdbcAccountErasureRepository accountEraser;

    public DeleteUserHandler(
        AuthService authService,
        JdbcUserRepository users,
        JdbcAccountErasureRepository accountEraser) {
        this.authService = Objects.requireNonNull(authService, "authService");
        this.users = Objects.requireNonNull(users, "users");
        this.accountEraser = Objects.requireNonNull(accountEraser, "accountEraser");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.post("/v1/admin/delete-user", this.chainWithAuth(this::handle, authFilter));
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
        DeleteUserRequest req = ctx.bodyAsClass(DeleteUserRequest.class);
        if (req.username == null || req.username.isBlank()) {
            ctx.status(400).json(Map.of("error", "username is required"));
            return;
        }
        var user = this.users.findFullByUsername(req.username).orElse(null);
        if (user == null) {
            ctx.status(404).json(Map.of("error", "用户不存在"));
            return;
        }
        this.accountEraser.erase(user.uuid(), Instant.now().toEpochMilli());
        this.authService.logout(user.uuid());
        ctx.status(200).json(Map.of("success", true));
    }

    static final class DeleteUserRequest {
        public String username;

        DeleteUserRequest() {
        }
    }
}
