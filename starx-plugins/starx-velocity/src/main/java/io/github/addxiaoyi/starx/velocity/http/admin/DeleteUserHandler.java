/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.auth.AuthResult;
import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.util.Map;
import java.util.Objects;

public final class DeleteUserHandler
implements AdminHandler {
    private final AuthService authService;

    public DeleteUserHandler(AuthService authService) {
        this.authService = Objects.requireNonNull(authService, "authService");
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
        AuthResult result = this.authService.deleteUser(req.username);
        if (result.success()) {
            ctx.status(200).json(Map.of("success", true));
        } else {
            ctx.status(400).json(Map.of("error", result.message()));
        }
    }

    static final class DeleteUserRequest {
        public String username;

        DeleteUserRequest() {
        }
    }
}
