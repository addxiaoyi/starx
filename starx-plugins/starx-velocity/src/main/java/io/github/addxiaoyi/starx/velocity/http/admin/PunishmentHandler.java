/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.database.JdbcPunishmentRepository;
import io.github.addxiaoyi.starx.common.model.Punishment;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PunishmentHandler
implements AdminHandler {
    private static final Set<String> VALID_TYPES = Set.of("BAN", "MUTE", "WARN", "KICK", "TEMPBAN", "TEMPMUTE");
    private static final int MAX_REASON_LENGTH = 500;
    private final JdbcPunishmentRepository repo;

    public PunishmentHandler(JdbcPunishmentRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.get("/v1/admin/punishments", this.chainWithAuth(this::handleList, authFilter));
        routes.post("/v1/admin/punishments", this.chainWithAuth(this::handleCreate, authFilter));
    }

    private RouteRegistrar.RouteHandler chainWithAuth(RouteRegistrar.RouteHandler handler, RouteRegistrar.RouteHandler ... authFilter) {
        return ctx -> {
            for (RouteRegistrar.RouteHandler filter : authFilter) {
                filter.handle(ctx);
            }
            handler.handle(ctx);
        };
    }

    private void handleList(JsonHttpExchange ctx) throws IOException {
        List<Punishment> result;
        String player = ctx.queryParam("player");
        if (player != null && !player.isBlank()) {
            try {
                result = this.repo.findByPlayer(UUID.fromString(player));
            }
            catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "Invalid UUID format"));
                return;
            }
        } else {
            result = this.repo.findAll();
        }
        ctx.status(200).json(result);
    }

    private void handleCreate(JsonHttpExchange ctx) throws Exception {
        PunishmentRequest req = ctx.bodyAsClass(PunishmentRequest.class);
        if (req.targetUuid == null) {
            ctx.status(400).json(Map.of("error", "target_uuid is required"));
            return;
        }
        if (req.type == null) {
            ctx.status(400).json(Map.of("error", "type is required"));
            return;
        }
        if (req.staffUuid == null) {
            ctx.status(400).json(Map.of("error", "staff_uuid is required"));
            return;
        }
        String normalizedType;
        String targetName;
        String staffName;
        try {
            normalizedType = AdminInput.enumValue(req.type, "type", VALID_TYPES);
            targetName = AdminFieldPolicy.minecraftName(req.targetName, "target_name");
            staffName = AdminFieldPolicy.staffName(req.staffName);
        }
        catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
            return;
        }
        if (req.reason != null && req.reason.length() > 500) {
            ctx.status(400).json(Map.of("error", "reason too long (max 500 characters)"));
            return;
        }
        Punishment p = new Punishment(UUID.randomUUID().toString(), req.targetUuid, targetName, normalizedType, req.reason != null ? this.sanitizeInput(req.reason) : null, req.staffUuid, staffName, System.currentTimeMillis(), req.expiresAt, true);
        this.repo.record(p);
        ctx.status(201).json(Map.of("id", p.id(), "success", true));
    }

    private String sanitizeInput(String input) {
        if (input == null) {
            return null;
        }
        return input.replaceAll("[<>\"'&]", "");
    }

    static final class PunishmentRequest {
        public UUID targetUuid;
        public String targetName;
        public String type;
        public String reason;
        public UUID staffUuid;
        public String staffName;
        public Long expiresAt;

        PunishmentRequest() {
        }
    }
}
