/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.database.JdbcAnnouncementRepository;
import io.github.addxiaoyi.starx.common.model.Announcement;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AnnouncementHandler
implements AdminHandler {
    private final JdbcAnnouncementRepository repo;

    public AnnouncementHandler(JdbcAnnouncementRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.post("/v1/admin/announcements", this.chainWithAuth(this::handleCreate, authFilter));
        routes.get("/v1/admin/announcements", this.chainWithAuth(this::handleList, authFilter));
        routes.post("/v1/admin/announcements/read", this.chainWithAuth(this::handleRead, authFilter));
    }

    private RouteRegistrar.RouteHandler chainWithAuth(RouteRegistrar.RouteHandler handler, RouteRegistrar.RouteHandler ... authFilter) {
        return ctx -> {
            for (RouteRegistrar.RouteHandler filter : authFilter) {
                filter.handle(ctx);
            }
            handler.handle(ctx);
        };
    }

    private void handleCreate(JsonHttpExchange ctx) throws Exception {
        AnnouncementRequest req = ctx.bodyAsClass(AnnouncementRequest.class);
        String title;
        String content;
        String createdBy;
        try {
            title = AdminFieldPolicy.announcementTitle(req.title);
            content = AdminFieldPolicy.announcementContent(req.content);
            createdBy = AdminFieldPolicy.actorId(
                    req.createdBy == null ? "console" : req.createdBy, "created_by");
        }
        catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
            return;
        }
        Announcement a = new Announcement(UUID.randomUUID().toString(), title, content, this.sanitizeInput(createdBy), System.currentTimeMillis(), req.expiresAt);
        this.repo.create(a);
        ctx.status(201).json(Map.of("id", a.id(), "success", true));
    }

    private void handleList(JsonHttpExchange ctx) throws IOException {
        List<Announcement> result;
        String player = ctx.queryParam("player");
        if (player != null && !player.isBlank()) {
            try {
                result = this.repo.findUnreadByPlayer(UUID.fromString(player));
            }
            catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "Invalid UUID format"));
                return;
            }
        } else {
            result = this.repo.findActive();
        }
        ctx.status(200).json(result);
    }

    private void handleRead(JsonHttpExchange ctx) throws Exception {
        ReadRequest req = ctx.bodyAsClass(ReadRequest.class);
        if (req.playerUuid == null) {
            ctx.status(400).json(Map.of("error", "announcement_id and player_uuid are required"));
            return;
        }
        String announcementId;
        try {
            announcementId = AdminFieldPolicy.actorId(req.announcementId, "announcement_id");
        }
        catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
            return;
        }
        this.repo.markRead(announcementId, req.playerUuid);
        ctx.status(200).json(Map.of("success", true));
    }

    private String sanitizeInput(String input) {
        if (input == null) {
            return null;
        }
        return input.replaceAll("[<>\"'&]", "");
    }

    static final class AnnouncementRequest {
        public String title;
        public String content;
        public String createdBy;
        public Long expiresAt;

        AnnouncementRequest() {
        }
    }

    static final class ReadRequest {
        public String announcementId;
        public UUID playerUuid;

        ReadRequest() {
        }
    }
}
