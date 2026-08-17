/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.repository.UserRepository;
import io.github.addxiaoyi.starx.common.database.JdbcPunishmentRepository;
import io.github.addxiaoyi.starx.common.model.Punishment;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class BanHandler
implements AdminHandler {
    private static final Set<String> VALID_TYPES = Set.of("BAN", "TEMPBAN");
    private final UserRepository users;
    private final EventBus eventBus;
    private final JdbcPunishmentRepository punishmentRepo;
    private final Function<UUID, Optional<UserDto>> identityAwareUserLookup;
    private final Function<String, Optional<UserDto>> identityAwareUsernameLookup;
    private final Function<UUID, UUID> canonicalUuidResolver;
    private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;

    public BanHandler(UserRepository users, EventBus eventBus, JdbcPunishmentRepository punishmentRepo) {
        this(users, eventBus, punishmentRepo, users::findByUuid, users::findByUsername,
            uuid -> uuid, uuid -> Set.of(uuid));
    }

    public BanHandler(
        UserRepository users,
        EventBus eventBus,
        JdbcPunishmentRepository punishmentRepo,
        Function<UUID, Optional<UserDto>> identityAwareUserLookup,
        Function<UUID, UUID> canonicalUuidResolver) {
        this(users, eventBus, punishmentRepo, identityAwareUserLookup, users::findByUsername,
            canonicalUuidResolver, uuid -> Set.of(uuid));
    }

    public BanHandler(
        UserRepository users,
        EventBus eventBus,
        JdbcPunishmentRepository punishmentRepo,
        Function<UUID, Optional<UserDto>> identityAwareUserLookup,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
        this(users, eventBus, punishmentRepo, identityAwareUserLookup, users::findByUsername,
            canonicalUuidResolver, knownMinecraftUuidsResolver);
    }

    public BanHandler(
        UserRepository users,
        EventBus eventBus,
        JdbcPunishmentRepository punishmentRepo,
        Function<UUID, Optional<UserDto>> identityAwareUserLookup,
        Function<String, Optional<UserDto>> identityAwareUsernameLookup,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
        this.users = Objects.requireNonNull(users, "users");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.punishmentRepo = Objects.requireNonNull(punishmentRepo, "punishmentRepo");
        this.identityAwareUserLookup = Objects.requireNonNull(identityAwareUserLookup, "identityAwareUserLookup");
        this.identityAwareUsernameLookup = Objects.requireNonNull(
            identityAwareUsernameLookup, "identityAwareUsernameLookup");
        this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
        this.knownMinecraftUuidsResolver = Objects.requireNonNull(
            knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.get("/v1/ban", this.chainWithAuth(this::handleQuery, authFilter));
        routes.post("/v1/admin/ban", this.chainWithAuth(this::handleBan, authFilter));
        routes.post("/v1/admin/ban/player", this.chainWithAuth(this::handleBanPlayer, authFilter));
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
        String name = ctx.queryParam("name");
        if (name == null || name.isBlank()) {
            ctx.status(400).json(Map.of("error", "name is required"));
            return;
        }
        if (name.length() > 16) {
            ctx.status(400).json(Map.of("error", "name too long"));
            return;
        }
        boolean isBanned = false;
        String banReason = null;
        Long banExpiresAt = null;
        Optional<UUID> uuidOpt = this.resolveNameToUuid(name);
        if (uuidOpt.isPresent()) {
            UUID uuid = uuidOpt.get();
            List<Punishment> activeBans = this.punishmentRepo.findActiveByTargetUuids(
                this.knownMinecraftUuidsResolver.apply(uuid));
            for (Punishment ban : activeBans) {
                if (!"BAN".equals(ban.type()) && !"TEMPBAN".equals(ban.type())) continue;
                isBanned = true;
                banReason = ban.reason();
                banExpiresAt = ban.expiresAt();
                break;
            }
        }
        ctx.status(200).json(Map.of("banned", isBanned, "name", name, "reason", banReason != null ? banReason : "", "expiresAt", banExpiresAt != null ? banExpiresAt : 0L));
    }

    private void handleBan(JsonHttpExchange ctx) throws Exception {
        BanRequest req = ctx.bodyAsClass(BanRequest.class);
        if (req.username == null || req.username.isBlank()) {
            ctx.status(400).json(Map.of("error", "username is required"));
            return;
        }
        if (req.username.length() > 16) {
            ctx.status(400).json(Map.of("error", "username too long"));
            return;
        }
        if (req.reason != null && req.reason.length() > 500) {
            ctx.status(400).json(Map.of("error", "reason too long (max 500 characters)"));
            return;
        }
        var target = this.identityAwareUsernameLookup.apply(req.username).orElse(null);
        if (target == null) {
            ctx.status(404).json(Map.of("error", "User not found"));
            return;
        }
        String reason = req.reason == null || req.reason.isBlank()
            ? "Banned by admin" : req.reason;
        Punishment punishment = new Punishment(
            UUID.randomUUID().toString(),
            canonicalUuidResolver.apply(target.uuid()),
            req.username,
            "BAN",
            reason,
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            "console",
            System.currentTimeMillis(),
            null,
            true);
        this.punishmentRepo.record(punishment);
        this.eventBus.publish("admin:ban:player", Map.of(
            "username", req.username, "reason", reason));
        ctx.status(201).json(Map.of("id", punishment.id(), "success", true));
    }

    private void handleBanPlayer(JsonHttpExchange ctx) throws Exception {
        BanPlayerRequest req = ctx.bodyAsClass(BanPlayerRequest.class);
        if (req.playerUuid == null) {
            ctx.status(400).json(Map.of("error", "player_uuid is required"));
            return;
        }
        if (req.type == null || !VALID_TYPES.contains(req.type)) {
            ctx.status(400).json(Map.of("error", "type must be BAN or TEMPBAN"));
            return;
        }
        if (req.reason == null || req.reason.isBlank()) {
            ctx.status(400).json(Map.of("error", "reason is required"));
            return;
        }
        if (req.reason.length() > 500) {
            ctx.status(400).json(Map.of("error", "reason too long (max 500 characters)"));
            return;
        }
        UserDto target = this.identityAwareUserLookup.apply(req.playerUuid).orElse(null);
        if (target == null) {
            ctx.status(404).json(Map.of("error", "Player not found"));
            return;
        }
        UUID playerUuid = canonicalUuidResolver.apply(req.playerUuid);
        UUID staffUuid = req.staffUuid != null
            ? canonicalUuidResolver.apply(req.staffUuid)
            : UUID.fromString("00000000-0000-0000-0000-000000000000");
        Punishment p = new Punishment(UUID.randomUUID().toString(), playerUuid, target.username(), req.type, req.reason, staffUuid, req.staffName != null ? req.staffName : "console", System.currentTimeMillis(), req.expiresAt, true);
        this.punishmentRepo.record(p);
        this.eventBus.publish("admin:ban:player", Map.of("playerUuid", playerUuid.toString(), "reason", req.reason));
        ctx.status(201).json(Map.of("id", p.id(), "success", true));
    }

    private Optional<UUID> resolveNameToUuid(String name) {
        return this.identityAwareUsernameLookup.apply(name)
            .map(u -> canonicalUuidResolver.apply(u.uuid()));
    }

    static final class BanRequest {
        public String username;
        public String reason;

        BanRequest() {
        }
    }

    static final class BanPlayerRequest {
        public UUID playerUuid;
        public String type;
        public String reason;
        public UUID staffUuid;
        public String staffName;
        public Long expiresAt;

        BanPlayerRequest() {
        }
    }
}
