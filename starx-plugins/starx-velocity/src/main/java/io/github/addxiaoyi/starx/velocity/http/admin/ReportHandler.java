/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.database.JdbcReportRepository;
import io.github.addxiaoyi.starx.common.model.Report;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class ReportHandler
implements AdminHandler {
    private static final Set<String> VALID_CATEGORIES = Set.of("BUG_ABUSE", "CHEATING", "HARASSMENT", "OTHER", "SCAM", "SPAM");
    private static final Set<String> VALID_STATUSES = Set.of("DISMISSED", "PENDING", "RESOLVED");
    private final JdbcReportRepository repo;
    private final Function<UUID, UUID> canonicalUuidResolver;

    public ReportHandler(JdbcReportRepository repo) {
        this(repo, uuid -> uuid);
    }

    public ReportHandler(
        JdbcReportRepository repo,
        Function<UUID, UUID> canonicalUuidResolver) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.get("/v1/admin/reports", this.chainWithAuth(this::handleList, authFilter));
        routes.post("/v1/admin/reports", this.chainWithAuth(this::handleCreate, authFilter));
        routes.post("/v1/admin/reports/resolve", this.chainWithAuth(this::handleResolve, authFilter));
        routes.post("/v1/admin/reports/dismiss", this.chainWithAuth(this::handleDismiss, authFilter));
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
        List<Report> result;
        String status = ctx.queryParam("status");
        if (status != null && !status.isBlank()) {
            String normalizedStatus;
            try {
                normalizedStatus = AdminInput.enumValue(status, "status", VALID_STATUSES);
            }
            catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
                return;
            }
            result = this.repo.findByStatus(normalizedStatus);
        } else {
            result = this.repo.findAll();
        }
        ctx.status(200).json(result);
    }

    private void handleCreate(JsonHttpExchange ctx) throws Exception {
        ReportRequest req = ctx.bodyAsClass(ReportRequest.class);
        if (req.reporterUuid == null || req.targetUuid == null || req.category == null) {
            ctx.status(400).json(Map.of("error", "reporter_uuid, target_uuid, category are required"));
            return;
        }
        String normalizedCategory;
        try {
            normalizedCategory = AdminInput.enumValue(req.category, "category", VALID_CATEGORIES);
        }
        catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
            return;
        }
        String details;
        try {
            details = AdminFieldPolicy.reportDetails(req.details);
        }
        catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
            return;
        }
        Report r = new Report(UUID.randomUUID().toString(), this.canonicalUuidResolver.apply(req.reporterUuid), this.canonicalUuidResolver.apply(req.targetUuid), normalizedCategory, details != null ? this.sanitizeInput(details) : null, "PENDING", null, null);
        this.repo.create(r);
        ctx.status(201).json(Map.of("id", r.id(), "success", true));
    }

    private void handleResolve(JsonHttpExchange ctx) throws Exception {
        ActionRequest req = ctx.bodyAsClass(ActionRequest.class);
        String id;
        String resolvedBy;
        try {
            id = AdminFieldPolicy.actorId(req.id, "id");
            resolvedBy = AdminFieldPolicy.actorId(req.resolvedBy, "resolved_by");
        }
        catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
            return;
        }
        if (this.repo.findById(id).isEmpty()) {
            ctx.status(404).json(Map.of("error", "report not found"));
            return;
        }
        if (!this.repo.resolve(id, this.sanitizeInput(resolvedBy))) {
            ctx.status(409).json(Map.of("error", "report is already closed"));
            return;
        }
        ctx.status(200).json(Map.of("success", true));
    }

    private void handleDismiss(JsonHttpExchange ctx) throws Exception {
        ActionRequest req = ctx.bodyAsClass(ActionRequest.class);
        String id;
        String resolvedBy;
        try {
            id = AdminFieldPolicy.actorId(req.id, "id");
            resolvedBy = AdminFieldPolicy.actorId(req.resolvedBy, "resolved_by");
        }
        catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
            return;
        }
        if (this.repo.findById(id).isEmpty()) {
            ctx.status(404).json(Map.of("error", "report not found"));
            return;
        }
        if (!this.repo.dismiss(id, this.sanitizeInput(resolvedBy))) {
            ctx.status(409).json(Map.of("error", "report is already closed"));
            return;
        }
        ctx.status(200).json(Map.of("success", true));
    }

    private String sanitizeInput(String input) {
        if (input == null) {
            return null;
        }
        return input.replaceAll("[<>\"'&]", "");
    }

    static final class ReportRequest {
        public UUID reporterUuid;
        public UUID targetUuid;
        public String category;
        public String details;

        ReportRequest() {
        }
    }

    static final class ActionRequest {
        public String id;
        public String resolvedBy;

        ActionRequest() {
        }
    }
}
