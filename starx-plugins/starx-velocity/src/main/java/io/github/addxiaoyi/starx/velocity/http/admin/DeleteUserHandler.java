/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.auth.AuthResult;
import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.common.account.JdbcAccountDeletionRepository;
import io.github.addxiaoyi.starx.common.account.JdbcAccountErasureRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;

public final class DeleteUserHandler
implements AdminHandler {
    private final AuthService authService;
    private final JdbcUserRepository users;
    private final JdbcAccountErasureRepository accountEraser;
    private final JdbcAccountDeletionRepository accountDeletions;
    private final Function<UUID, UUID> canonicalUuidResolver;
    private final Function<String, java.util.Optional<StarxUser>> usernameResolver;
    private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;

    public DeleteUserHandler(
        AuthService authService,
        JdbcUserRepository users,
        JdbcAccountErasureRepository accountEraser) {
        this(authService, users, accountEraser, Function.identity(), users::findFullByUsername,
            uuid -> Set.of(uuid), null);
    }

    public DeleteUserHandler(
        AuthService authService,
        JdbcUserRepository users,
        JdbcAccountErasureRepository accountEraser,
        Function<UUID, UUID> canonicalUuidResolver) {
        this(authService, users, accountEraser, canonicalUuidResolver, users::findFullByUsername,
            uuid -> Set.of(uuid), null);
    }

    public DeleteUserHandler(
        AuthService authService,
        JdbcUserRepository users,
        JdbcAccountErasureRepository accountEraser,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<String, java.util.Optional<StarxUser>> usernameResolver) {
        this(authService, users, accountEraser, canonicalUuidResolver, usernameResolver,
            uuid -> Set.of(uuid), null);
    }

    public DeleteUserHandler(
        AuthService authService,
        JdbcUserRepository users,
        JdbcAccountErasureRepository accountEraser,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<String, java.util.Optional<StarxUser>> usernameResolver,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
        this(authService, users, accountEraser, canonicalUuidResolver, usernameResolver,
            knownMinecraftUuidsResolver, null, ignored -> { });
    }

    public DeleteUserHandler(
        AuthService authService,
        JdbcUserRepository users,
        JdbcAccountErasureRepository accountEraser,
        JdbcAccountDeletionRepository accountDeletions,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<String, java.util.Optional<StarxUser>> usernameResolver,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver,
        Consumer<UUID> disconnect) {
        this(authService, users, accountEraser, canonicalUuidResolver, usernameResolver,
            knownMinecraftUuidsResolver, accountDeletions, disconnect);
    }

    public DeleteUserHandler(
        AuthService authService,
        JdbcUserRepository users,
        JdbcAccountErasureRepository accountEraser,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<String, java.util.Optional<StarxUser>> usernameResolver,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver,
        Consumer<UUID> disconnect) {
        this(authService, users, accountEraser, canonicalUuidResolver, usernameResolver,
            knownMinecraftUuidsResolver, null, disconnect);
    }

    private DeleteUserHandler(
        AuthService authService,
        JdbcUserRepository users,
        JdbcAccountErasureRepository accountEraser,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<String, java.util.Optional<StarxUser>> usernameResolver,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver,
        JdbcAccountDeletionRepository accountDeletions,
        Consumer<UUID> disconnect) {
        this.authService = Objects.requireNonNull(authService, "authService");
        this.users = Objects.requireNonNull(users, "users");
        this.accountEraser = Objects.requireNonNull(accountEraser, "accountEraser");
        this.accountDeletions = accountDeletions;
        this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
        this.usernameResolver = Objects.requireNonNull(usernameResolver, "usernameResolver");
        this.knownMinecraftUuidsResolver = Objects.requireNonNull(
            knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
        this.disconnect = Objects.requireNonNull(disconnect, "disconnect");
    }

    private final Consumer<UUID> disconnect;

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
        var user = this.usernameResolver.apply(req.username).orElse(null);
        if (user == null) {
            ctx.status(404).json(Map.of("error", "用户不存在"));
            return;
        }
        UUID playerUuid = this.canonicalUuidResolver.apply(user.uuid());
        Set<UUID> sessionUuids = knownSessionUuids(user.uuid(), playerUuid);
        long erasedAt = Instant.now().toEpochMilli();
        if (this.accountDeletions == null) {
            this.accountEraser.erase(playerUuid, erasedAt);
        } else {
            this.accountEraser.eraseAndCompletePending(
                this.accountDeletions, playerUuid, sessionUuids, erasedAt);
        }
        this.logoutKnownSessions(sessionUuids);
        this.disconnectKnownSessions(sessionUuids);
        ctx.status(200).json(Map.of("success", true));
    }

    private void logoutKnownSessions(Set<UUID> sessionUuids) {
        for (UUID sessionUuid : sessionUuids) {
            this.authService.forceLogoutInternal(sessionUuid, "admin-deleted");
        }
    }

    private void disconnectKnownSessions(Set<UUID> sessionUuids) {
        for (UUID sessionUuid : sessionUuids) {
            this.disconnect.accept(sessionUuid);
        }
    }

    private Set<UUID> knownSessionUuids(UUID requestedUuid, UUID canonicalUuid) {
        Set<UUID> resolved = Objects.requireNonNull(
            this.knownMinecraftUuidsResolver.apply(requestedUuid),
            "knownMinecraftUuidsResolver returned null");
        Set<UUID> sessionUuids = new LinkedHashSet<>(resolved);
        sessionUuids.add(canonicalUuid);
        sessionUuids.remove(null);
        return Set.copyOf(sessionUuids);
    }

    static final class DeleteUserRequest {
        public String username;

        DeleteUserRequest() {
        }
    }
}
