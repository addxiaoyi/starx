/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.database.JdbcStaffNoteRepository;
import io.github.addxiaoyi.starx.common.model.StaffNote;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class StaffNoteHandler
implements AdminHandler {
    private final JdbcStaffNoteRepository repo;

    public StaffNoteHandler(JdbcStaffNoteRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.get("/v1/admin/notes", this.chainWithAuth(this::handleList, authFilter));
        routes.post("/v1/admin/notes", this.chainWithAuth(this::handleCreate, authFilter));
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
        List<StaffNote> result;
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
        String severity;
        NoteRequest req = ctx.bodyAsClass(NoteRequest.class);
        if (req.targetUuid == null) {
            ctx.status(400).json(Map.of("error", "target_uuid is required"));
            return;
        }
        String noteText;
        try {
            noteText = AdminFieldPolicy.staffNote(req.note);
        }
        catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
            return;
        }
        String string = severity = req.severity != null ? req.severity.toUpperCase() : "INFO";
        if (!(severity.equals("INFO") || severity.equals("WARNING") || severity.equals("CRITICAL"))) {
            ctx.status(400).json(Map.of("error", "severity must be INFO, WARNING, or CRITICAL"));
            return;
        }
        StaffNote note = new StaffNote(UUID.randomUUID().toString(), req.targetUuid, this.sanitizeInput(noteText), severity, req.staffUuid != null ? req.staffUuid : UUID.fromString("00000000-0000-0000-0000-000000000000"), System.currentTimeMillis());
        this.repo.addNote(note);
        ctx.status(201).json(Map.of("id", note.id(), "success", true));
    }

    private String sanitizeInput(String input) {
        if (input == null) {
            return null;
        }
        return input.replaceAll("[<>\"'&]", "");
    }

    static final class NoteRequest {
        public UUID targetUuid;
        public String note;
        public String severity;
        public UUID staffUuid;
        NoteRequest() {
        }
    }
}
