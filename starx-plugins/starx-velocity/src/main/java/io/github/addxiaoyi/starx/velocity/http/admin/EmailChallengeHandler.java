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
    } catch (IllegalArgumentException error) {
      ctx.status(400).json(Map.of("error", Objects.requireNonNullElse(
          error.getMessage(), "\u90ae\u7bb1\u5730\u5740\u6216\u8d26\u53f7\u65e0\u6548")));
    } catch (io.github.addxiaoyi.starx.common.binding.JdbcBindingChallengeRepository.ChallengeInProgressException error) {
      ctx.status(409).json(Map.of(
          "error", "\u5df2\u6709\u9a8c\u8bc1\u7801\u6b63\u5728\u5904\u7406\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5",
          "code", "email_challenge_in_progress"));
    } catch (IllegalStateException error) {
      ctx.status(503).json(Map.of(
          "error", Objects.requireNonNullElse(error.getMessage(), "\u90ae\u4ef6\u53d1\u9001\u670d\u52a1\u6682\u4e0d\u53ef\u7528"),
          "code", "email_delivery_unavailable"));
    } catch (RuntimeException error) {
      ctx.status(500).json(Map.of(
          "error", "\u90ae\u4ef6\u53d1\u9001\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5",
          "code", "email_delivery_failed"));
    }
  }

  private void confirm(JsonHttpExchange ctx) throws Exception {
    Request request = ctx.bodyAsClass(Request.class);
    try {
      UUID playerId = uuid(request);
      boolean bound = this.challenges.confirmAndExecute(
          playerId, request.code,
          (operationId, email) -> this.auth.bindEmail(playerId, email).success());
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
