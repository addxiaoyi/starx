package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.database.JdbcBindingRepository;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class BindingUnlinkHandler implements AdminHandler {
  private final JdbcBindingRepository bindings;
  private final Function<UUID, UUID> canonicalUuidResolver;
  private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;

  public BindingUnlinkHandler(JdbcBindingRepository bindings) {
    this(bindings, Function.identity(), uuid -> Set.of(uuid));
  }

  public BindingUnlinkHandler(
      JdbcBindingRepository bindings,
      Function<UUID, UUID> canonicalUuidResolver,
      Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
    this.knownMinecraftUuidsResolver = Objects.requireNonNull(
        knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
  }

  @Override public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... authFilters) {
    routes.post("/v1/user/bindings/unlink", chain(this::unlink, authFilters));
  }

  private RouteRegistrar.RouteHandler chain(RouteRegistrar.RouteHandler handler,
                                             RouteRegistrar.RouteHandler... filters) {
    return context -> {
      for (RouteRegistrar.RouteHandler filter : filters) filter.handle(context);
      handler.handle(context);
    };
  }

  private void unlink(JsonHttpExchange context) throws Exception {
    Request request = context.bodyAsClass(Request.class);
    if (request.playerUuid == null || request.kind == null) {
      context.status(400).json(Map.of("ok", false, "error", "playerUuid and kind are required"));
      return;
    }
    try {
      UUID canonicalUuid = canonicalUuidResolver.apply(request.playerUuid);
      long changedAt = System.currentTimeMillis();
      LinkedHashSet<UUID> knownUuids = new LinkedHashSet<>();
      knownUuids.add(canonicalUuid);
      knownUuids.addAll(Objects.requireNonNull(
          knownMinecraftUuidsResolver.apply(request.playerUuid),
          "knownMinecraftUuidsResolver returned null"));
      boolean changed = false;
      for (UUID knownUuid : knownUuids) {
        if (knownUuid != null) {
          changed |= bindings.unbind(knownUuid, request.kind, "website", changedAt);
        }
      }
      context.status(200).json(Map.of("ok", true, "changed", changed));
    } catch (IllegalArgumentException error) {
      context.status(400).json(Map.of("ok", false, "error", error.getMessage()));
    }
  }

  static final class Request {
    public UUID playerUuid;
    public String kind;
    Request() {}
  }
}
