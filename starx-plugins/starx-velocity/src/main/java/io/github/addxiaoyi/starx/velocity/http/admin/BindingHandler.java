/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.api.dto.UserDto;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class BindingHandler
implements AdminHandler {
    private final JdbcBindingRepository repo;
    private final JdbcUserRepository userRepo;
    private final BindingVerificationService verificationService;
    private final BindingCodeResolver codeResolver;
    private final Function<UUID, Optional<UserDto>> identityAwareUserLookup;
    private final Function<UUID, UUID> canonicalUuidResolver;
    private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;

    public BindingHandler(JdbcBindingRepository repo, JdbcUserRepository userRepo, BindingVerificationService verificationService) {
        this(repo, userRepo, verificationService, null, uuid -> uuid, uuid -> Set.of(uuid));
    }

    public BindingHandler(
        JdbcBindingRepository repo,
        JdbcUserRepository userRepo,
        BindingVerificationService verificationService,
        Function<UUID, Optional<UserDto>> identityAwareLookup) {
        this(repo, userRepo, verificationService, identityAwareLookup, uuid -> uuid, uuid -> Set.of(uuid));
    }

    public BindingHandler(
        JdbcBindingRepository repo,
        JdbcUserRepository userRepo,
        BindingVerificationService verificationService,
        Function<UUID, Optional<UserDto>> identityAwareLookup,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.userRepo = Objects.requireNonNull(userRepo, "userRepo");
        this.verificationService = Objects.requireNonNull(verificationService, "verificationService");
        this.identityAwareUserLookup = identityAwareLookup == null
            ? userRepo::findByUuid
            : candidate -> userRepo.findByUuid(candidate).or(() -> identityAwareLookup.apply(candidate));
        this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
        this.knownMinecraftUuidsResolver = Objects.requireNonNull(
            knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
        this.codeResolver = identityAwareLookup == null
            ? new BindingCodeResolver(verificationService, userRepo)
            : new BindingCodeResolver(verificationService, userRepo, identityAwareLookup);
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
            Optional<UUID> parsedPlayer = parsePlayerUuid(player);
            if (parsedPlayer.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Invalid UUID format"));
                return;
            }
            UUID requested = parsedPlayer.orElseThrow();
            UUID canonical = this.canonicalUuidResolver.apply(requested);
            result = this.repo.findByPlayer(canonical);
            if (result.isEmpty()) {
                for (UUID known : this.knownMinecraftUuidsResolver.apply(requested)) {
                    if (known.equals(canonical)) continue;
                    result = this.repo.findByPlayer(known);
                    if (result.isPresent()) break;
                }
            }
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

    private static Optional<UUID> parsePlayerUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private void handleSave(JsonHttpExchange ctx) throws Exception {
        SaveRequest req = ctx.bodyAsClass(SaveRequest.class);
        if (req.playerUuid == null) {
            ctx.status(400).json(Map.of("error", "player_uuid is required"));
            return;
        }
        UUID canonicalUuid = this.canonicalUuidResolver.apply(req.playerUuid);
        PlayerBinding binding = new PlayerBinding(
            canonicalUuid, req.qqId, req.discordId,
            System.currentTimeMillis());
        if (!this.repo.migrateAndSave(
            this.knownMinecraftUuidsResolver.apply(canonicalUuid), binding)) {
            ctx.status(409).json(Map.of("error", "Binding identities conflict"));
            return;
        }
        ctx.status(200).json(Map.of("success", true));
    }

    private void handleGenerateCode(JsonHttpExchange ctx) throws Exception {
        CodeRequest req = ctx.bodyAsClass(CodeRequest.class);
        if (req.playerUuid == null) {
            ctx.status(400).json(Map.of("error", "player_uuid is required"));
            return;
        }
        if (this.identityAwareUserLookup.apply(req.playerUuid).isEmpty()) {
            ctx.status(404).json(Map.of("error", "Player not found"));
            return;
        }
        String code = this.verificationService.generateCode(this.canonicalUuidResolver.apply(req.playerUuid));
        ctx.status(200).json(Map.of("code", code, "message", "Send this code to the QQ bot"));
    }

    private void handleVerify(JsonHttpExchange ctx) throws Exception {
        VerifyRequest req = ctx.bodyAsClass(VerifyRequest.class);
        if (req.code == null || req.code.isBlank()) {
            ctx.status(400).json(Map.of("error", "code is required"));
            return;
        }
        UUID playerUuid = this.verificationService.verifyAndExecute(req.code, (operationId, candidate) -> {
            UUID canonicalUuid = this.canonicalUuidResolver.apply(candidate);
            PlayerBinding binding = new PlayerBinding(
                canonicalUuid, req.qqId, null, System.currentTimeMillis());
            return this.repo.migrateAndSave(
                this.knownMinecraftUuidsResolver.apply(canonicalUuid), binding);
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
