package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.common.auth.EmailChallengeService;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class EmailChallengeHandler implements AdminHandler {
  private final EmailChallengeService challenges;
  private final AuthService auth;

  public EmailChallengeHandler(EmailChallengeService challenges, AuthService auth) {
    this.challenges = Objects.requireNonNull(challenges, "challenges");
    this.auth = Objects.requireNonNull(auth, "auth");
  }

  @Override
  public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... filters) {
    routes.post("/v1/admin/email-challenge/send", chain(this::send, filters));
    routes.post("/v1/admin/email-challenge/confirm", chain(this::confirm, filters));
  }

  private RouteRegistrar.RouteHandler chain(
      RouteRegistrar.RouteHandler handler, RouteRegistrar.RouteHandler... filters) {
    return ctx -> { for (var filter : filters) filter.handle(ctx); handler.handle(ctx); };
  }

  private void send(JsonHttpExchange ctx) throws Exception {
    Request request = ctx.bodyAsClass(Request.class);
    try {
      this.challenges.begin(uuid(request), request.email);
      ctx.status(200).json(Map.of("success", true, "message", "验证码已发送"));
    } catch (RuntimeException error) {
      ctx.status(400).json(Map.of("error", error.getMessage()));
    }
  }

  private void confirm(JsonHttpExchange ctx) throws Exception {
    Request request = ctx.bodyAsClass(Request.class);
    try {
      UUID playerId = uuid(request);
      boolean bound = this.challenges.confirmAndExecute(
          playerId, request.code,
          email -> this.auth.bindEmail(playerId, email).success());
      if (!bound) {
        ctx.status(400).json(Map.of("error", "邮箱验证码无效、已使用，或绑定失败"));
        return;
      }
      ctx.status(200).json(Map.of("success", true, "message", "邮箱绑定成功"));
    } catch (RuntimeException error) {
      ctx.status(400).json(Map.of("error", error.getMessage()));
    }
  }

  private static UUID uuid(Request request) {
    try { return UUID.fromString(Objects.requireNonNullElse(request.uuid, "")); }
    catch (IllegalArgumentException error) { throw new IllegalArgumentException("uuid is invalid"); }
  }

  static final class Request {
    String uuid;
    String email;
    String code;
  }
}
