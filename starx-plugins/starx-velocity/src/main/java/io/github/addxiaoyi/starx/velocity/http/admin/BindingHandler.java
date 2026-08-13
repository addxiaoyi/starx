/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.auth.BindingVerificationService;
import io.github.addxiaoyi.starx.common.database.JdbcBindingRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BindingHandler
implements AdminHandler {
    private final JdbcBindingRepository repo;
    private final JdbcUserRepository userRepo;
    private final BindingVerificationService verificationService;
    private final BindingCodeResolver codeResolver;

    public BindingHandler(JdbcBindingRepository repo, JdbcUserRepository userRepo, BindingVerificationService verificationService) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.userRepo = Objects.requireNonNull(userRepo, "userRepo");
        this.verificationService = Objects.requireNonNull(verificationService, "verificationService");
        this.codeResolver = new BindingCodeResolver(verificationService, userRepo);
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.get("/v1/admin/bindings", this.chainWithAuth(this::handleQuery, authFilter));
        routes.post("/v1/admin/bindings", this.chainWithAuth(this::handleSave, authFilter));
        routes.post("/v1/admin/bindings/verify-code", this.chainWithAuth(this::handleGenerateCode, authFilter));
        routes.post("/v1/admin/bindings/verify", this.chainWithAuth(this::handleVerify, authFilter));
        routes.post("/v1/admin/bindings/resolve-code", this.chainWithAuth(this::handleResolveCode, authFilter));
    }

    private RouteRegistrar.RouteHandler chainWithAuth(RouteRegistrar.RouteHandler handler, RouteRegistrar.RouteHandler ... authFilter) {
        return ctx -> {
            for (RouteRegistrar.RouteHandler filter : authFilter) {
                filter.handle(ctx);
            }
            handler.handle(ctx);
        };
    }

    private void handleQuery(JsonHttpExchange ctx) throws IOException {
        Optional<PlayerBinding> result;
        String player = ctx.queryParam("player");
        String qq = ctx.queryParam("qq");
        String discord = ctx.queryParam("discord");
        if (player != null && !player.isBlank()) {
            result = this.repo.findByPlayer(UUID.fromString(player));
        } else if (qq != null && !qq.isBlank()) {
            result = this.repo.findByQq(qq);
        } else if (discord != null && !discord.isBlank()) {
            result = this.repo.findByDiscord(discord);
        } else {
            ctx.status(400).json(Map.of("error", "player, qq, or discord param is required"));
            return;
        }
        if (result.isPresent()) {
            ctx.status(200).json(result.get());
        } else {
            ctx.status(404).json(Map.of("error", "Binding not found"));
        }
    }

    private void handleSave(JsonHttpExchange ctx) throws Exception {
        SaveRequest req = ctx.bodyAsClass(SaveRequest.class);
        if (req.playerUuid == null) {
            ctx.status(400).json(Map.of("error", "player_uuid is required"));
            return;
        }
        PlayerBinding binding = new PlayerBinding(req.playerUuid, req.qqId, req.discordId, System.currentTimeMillis());
        this.repo.save(binding);
        ctx.status(200).json(Map.of("success", true));
    }

    private void handleGenerateCode(JsonHttpExchange ctx) throws Exception {
        CodeRequest req = ctx.bodyAsClass(CodeRequest.class);
        if (req.playerUuid == null) {
            ctx.status(400).json(Map.of("error", "player_uuid is required"));
            return;
        }
        if (!this.userRepo.existsByUuid(req.playerUuid)) {
            ctx.status(404).json(Map.of("error", "Player not found"));
            return;
        }
        String code = this.verificationService.generateCode(req.playerUuid);
        ctx.status(200).json(Map.of("code", code, "message", "Send this code to the QQ bot"));
    }

    private void handleVerify(JsonHttpExchange ctx) throws Exception {
        VerifyRequest req = ctx.bodyAsClass(VerifyRequest.class);
        if (req.code == null || req.code.isBlank()) {
            ctx.status(400).json(Map.of("error", "code is required"));
            return;
        }
        UUID playerUuid = this.verificationService.verifyAndExecute(req.code, (operationId, candidate) -> {
            PlayerBinding binding = new PlayerBinding(
                candidate, req.qqId, null, System.currentTimeMillis());
            return this.repo.save(binding);
        });
        if (playerUuid == null) {
            ctx.status(404).json(Map.of("error", "Invalid or expired code"));
            return;
        }
        ctx.status(200).json(Map.of("success", true, "player_uuid", playerUuid.toString()));
    }

    private void handleResolveCode(JsonHttpExchange ctx) throws Exception {
        ResolveRequest req = ctx.bodyAsClass(ResolveRequest.class);
        if (req.code == null || req.code.isBlank()) {
            ctx.status(400).json(Map.of("ok", false, "error", "code is required"));
            return;
        }
        Optional<BindingCodeResolver.Identity> identity = this.codeResolver.resolve(req.code.trim());
        if (identity.isEmpty()) {
            ctx.status(404).json(Map.of("ok", false, "error", "Invalid or expired code"));
            return;
        }
        BindingCodeResolver.Identity player = identity.get();
        ctx.status(200).json(Map.of(
            "ok", true,
            "playerUuid", player.playerUuid().toString(),
            "username", player.username()));
    }

    static final class SaveRequest {
        public UUID playerUuid;
        public String qqId;
        public String discordId;

        SaveRequest() {
        }
    }

    static final class CodeRequest {
        public UUID playerUuid;

        CodeRequest() {
        }
    }

    static final class VerifyRequest {
        public String code;
        public String qqId;

        VerifyRequest() {
        }
    }

    static final class ResolveRequest {
        public String code;

        ResolveRequest() {
        }
    }
}
