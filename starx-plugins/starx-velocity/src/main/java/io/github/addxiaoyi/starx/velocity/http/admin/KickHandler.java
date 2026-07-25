/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.proxy.Player
 *  com.velocitypowered.api.proxy.ProxyServer
 *  net.kyori.adventure.text.Component
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;

public final class KickHandler
implements AdminHandler {
    private static final int MAX_USERNAME_LENGTH = 16;
    private static final int MAX_REASON_LENGTH = 200;
    private final ProxyServer proxy;
    private final EventBus eventBus;

    public KickHandler(ProxyServer proxy, EventBus eventBus) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.post("/v1/admin/kick", this.chainWithAuth(this::handleKick, authFilter));
    }

    private RouteRegistrar.RouteHandler chainWithAuth(RouteRegistrar.RouteHandler handler, RouteRegistrar.RouteHandler ... authFilter) {
        return ctx -> {
            for (RouteRegistrar.RouteHandler filter : authFilter) {
                filter.handle(ctx);
            }
            handler.handle(ctx);
        };
    }

    private void handleKick(JsonHttpExchange ctx) throws Exception {
        KickRequest req = ctx.bodyAsClass(KickRequest.class);
        if (req.username == null || req.username.isBlank()) {
            ctx.status(400).json(Map.of("error", "username is required"));
            return;
        }
        if (req.username.length() > 16) {
            ctx.status(400).json(Map.of("error", "username too long"));
            return;
        }
        if (req.reason != null && req.reason.length() > 200) {
            ctx.status(400).json(Map.of("error", "reason too long (max 200 characters)"));
            return;
        }
        Optional online = this.proxy.getPlayer(req.username);
        if (online.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Player not online"));
            return;
        }
        String reason = req.reason == null || req.reason.isBlank() ? "Kicked by admin" : req.reason;
        ((Player)online.get()).disconnect((Component)Component.text((String)reason));
        this.eventBus.publish("admin:kick:player", Map.of("username", req.username, "reason", reason));
        ctx.status(200).json(Map.of("success", true));
    }

    static final class KickRequest {
        public String username;
        public String reason;

        KickRequest() {
        }
    }
}
