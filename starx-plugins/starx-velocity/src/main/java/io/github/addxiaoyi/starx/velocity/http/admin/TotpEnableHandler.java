package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.auth.AuthResult;
import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.common.auth.TotpEnrollment;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

public final class TotpEnableHandler implements AdminHandler {
  private final AuthService auth;

  public TotpEnableHandler(AuthService auth) {
    this.auth = Objects.requireNonNull(auth, "auth");
  }

  @Override
  public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... filters) {
    routes.post("/v1/admin/totp/setup", chain(this::setup, filters));
    routes.post("/v1/admin/totp/confirm", chain(this::confirm, filters));
    routes.post("/v1/admin/totp/disable", chain(this::disable, filters));
    routes.post("/v1/admin/totp/recovery-codes/rotate", chain(this::rotate, filters));
  }

  private RouteRegistrar.RouteHandler chain(
      RouteRegistrar.RouteHandler handler, RouteRegistrar.RouteHandler... filters) {
    return ctx -> {
      for (RouteRegistrar.RouteHandler filter : filters) filter.handle(ctx);
      handler.handle(ctx);
    };
  }

  private void setup(JsonHttpExchange ctx) throws Exception {
    Request request = ctx.bodyAsClass(Request.class);
    try {
      TotpEnrollment enrollment = this.auth.beginTotpEnrollment(uuid(request), request.password);
      ctx.status(200).json(Map.of(
          "success", true,
          "secret", enrollment.secret(),
          "otpauthUri", enrollment.otpauthUri(),
          "expiresAt", enrollment.expiresAt().toString()));
    } catch (IllegalArgumentException | IllegalStateException error) {
      ctx.status(400).json(Map.of("error", error.getMessage()));
    }
  }

  private void confirm(JsonHttpExchange ctx) throws Exception {
    Request request = ctx.bodyAsClass(Request.class);
    try {
      respond(ctx, this.auth.confirmTotpEnrollment(uuid(request), request.code));
    } catch (IllegalArgumentException error) {
      ctx.status(400).json(Map.of("error", error.getMessage()));
    }
  }

  private void disable(JsonHttpExchange ctx) throws Exception {
    Request request = ctx.bodyAsClass(Request.class);
    try {
      respond(ctx, this.auth.disableTotp(uuid(request), request.password));
    } catch (IllegalArgumentException error) {
      ctx.status(400).json(Map.of("error", error.getMessage()));
    }
  }

  private void rotate(JsonHttpExchange ctx) throws Exception {
    Request request = ctx.bodyAsClass(Request.class);
    try {
      respond(ctx, this.auth.rotateRecoveryCodes(uuid(request), request.code));
    } catch (IllegalArgumentException error) {
      ctx.status(400).json(Map.of("error", error.getMessage()));
    }
  }

  private static UUID uuid(Request request) {
    return parseUuid(request == null ? null : request.uuid);
  }

  static UUID parseUuid(String rawUuid) {
    try {
      return UUID.fromString(Objects.requireNonNullElse(rawUuid, ""));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("uuid is invalid");
    }
  }

  private static void respond(JsonHttpExchange ctx, AuthResult result) throws Exception {
    if (result.success()) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("success", true);
      response.put("message", result.message());
      if (result.totpSecret() != null) response.put("secret", result.totpSecret());
      if (!result.recoveryCodes().isEmpty()) response.put("recoveryCodes", result.recoveryCodes());
      ctx.status(200).json(Map.copyOf(response));
    } else {
      ctx.status(400).json(Map.of("error", result.message()));
    }
  }

  static final class Request {
    String uuid;
    String password;
    String code;
  }
}
