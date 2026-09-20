/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.database.JdbcVoteRepository;
import io.github.addxiaoyi.starx.common.database.VoteAlreadyCastException;
import io.github.addxiaoyi.starx.common.model.StaffVote;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import io.github.addxiaoyi.starx.velocity.http.admin.AdminHandler;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.function.Function;

public final class VoteHandler
implements AdminHandler {
    private final JdbcVoteRepository repo;
    private final Function<UUID, UUID> canonicalUuidResolver;
    private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;

    public VoteHandler(JdbcVoteRepository repo) {
        this(repo, uuid -> uuid, uuid -> Set.of(uuid));
    }

    public VoteHandler(
        JdbcVoteRepository repo,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
        this.knownMinecraftUuidsResolver = Objects.requireNonNull(
            knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
    }

    @Override
    public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler ... authFilter) {
        routes.get("/v1/admin/votes", this.chainWithAuth(this::handleList, authFilter));
        routes.get("/v1/admin/votes/active", this.chainWithAuth(this::handleActive, authFilter));
        routes.post("/v1/admin/votes/cast", this.chainWithAuth(this::handleCast, authFilter));
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
        ctx.status(200).json(this.repo.findAllActive());
    }

    private void handleActive(JsonHttpExchange ctx) throws IOException {
        Optional<StaffVote> active = this.repo.findActive();
        if (active.isPresent()) {
            ctx.status(200).json(active.get());
        } else {
            ctx.status(404).json(Map.of("error", "No active vote"));
        }
    }

    private void handleCast(JsonHttpExchange ctx) throws Exception {
        CastRequest req = ctx.bodyAsClass(CastRequest.class);
        if (req.voteId == null || req.voterUuid == null || req.vote == null) {
            ctx.status(400).json(Map.of("error", "vote_id, voter_uuid, and vote are required"));
            return;
        }
        if (!"YES".equalsIgnoreCase(req.vote) && !"NO".equalsIgnoreCase(req.vote)) {
            ctx.status(400).json(Map.of("error", "vote must be YES or NO"));
            return;
        }
        if (this.repo.hasVoted(req.voteId, this.knownMinecraftUuidsResolver.apply(req.voterUuid))) {
            ctx.status(409).json(Map.of("error", "Already voted"));
            return;
        }
        try {
            this.repo.castVote(
                req.voteId,
                this.canonicalUuidResolver.apply(req.voterUuid),
                "YES".equalsIgnoreCase(req.vote));
        } catch (VoteAlreadyCastException error) {
            ctx.status(409).json(Map.of("error", "Already voted"));
            return;
        }
        ctx.status(200).json(Map.of("success", true));
    }

    static final class CastRequest {
        public String voteId;
        public UUID voterUuid;
        public String vote;

        CastRequest() {
        }
    }
}
