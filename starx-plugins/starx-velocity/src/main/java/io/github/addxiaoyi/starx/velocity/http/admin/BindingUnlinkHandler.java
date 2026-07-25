package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.database.JdbcBindingRepository;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class BindingUnlinkHandler implements AdminHandler {
  private final JdbcBindingRepository bindings;

  public BindingUnlinkHandler(JdbcBindingRepository bindings) {
    this.bindings = Objects.requireNonNull(bindings, "bindings");
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
      boolean changed = bindings.unbind(
          request.playerUuid, request.kind, "website", System.currentTimeMillis());
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
