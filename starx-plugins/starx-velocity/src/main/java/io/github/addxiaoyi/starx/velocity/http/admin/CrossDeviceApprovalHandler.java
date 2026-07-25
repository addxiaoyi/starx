package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.auth.CrossDeviceApprovalService;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CrossDeviceApprovalHandler implements AdminHandler {
  private final CrossDeviceApprovalService approvals;
  private final String websiteOrigin;
  private final ApprovalExecutor executor;

  public CrossDeviceApprovalHandler(CrossDeviceApprovalService approvals, String websiteOrigin) {
    this(approvals, websiteOrigin, (ignored, email) -> true);
  }

  public CrossDeviceApprovalHandler(
      CrossDeviceApprovalService approvals,
      String websiteOrigin,
      ApprovalExecutor executor) {
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.websiteOrigin = normalizeOrigin(websiteOrigin);
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  @Override
  public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... authFilter) {
    routes.post("/v1/admin/approval/create", this.chain(this::createRequest, authFilter));
    routes.post("/v1/admin/approval/confirm", this.chain(this::confirmRequest, authFilter));
  }

  private RouteRegistrar.RouteHandler chain(
      RouteRegistrar.RouteHandler handler,
      RouteRegistrar.RouteHandler... authFilter
  ) {
    return ctx -> {
      for (RouteRegistrar.RouteHandler filter : authFilter) filter.handle(ctx);
      handler.handle(ctx);
    };
  }

  private void createRequest(JsonHttpExchange ctx) throws IOException {
    Request request = parse(ctx);
    Map<String, Object> response = create(request.playerId, request.username, request.action);
    ctx.status(Boolean.TRUE.equals(response.get("ok")) ? 201 : 400).json(response);
  }

  private void confirmRequest(JsonHttpExchange ctx) throws IOException {
    Request request = parse(ctx);
    CrossDeviceApprovalService.Action requested = action(request.action);
    CrossDeviceApprovalService.Approval approval = this.approvals.approveAndExecute(
        request.token, UUID.fromString(request.playerId), request.username, requested,
        challenge -> this.executor.execute(challenge, request.email));
    ctx.status(approval.success() ? 200 : 409).json(Map.of(
        "ok", approval.success(), "status", approval.status().name()));
  }

  Map<String, Object> create(String rawPlayerId, String username, String rawAction) {
    try {
      CrossDeviceApprovalService.Action action;
      try {
        action = action(rawAction);
      } catch (IllegalArgumentException error) {
        return Map.of("ok", false, "error", "invalid_action");
      }
      if (action == CrossDeviceApprovalService.Action.ENABLE_TOTP) {
        return Map.of("ok", false, "error", "totp_requires_game_confirmation");
      }
      if (action == CrossDeviceApprovalService.Action.APPROVE_LOGIN) {
        return Map.of("ok", false, "error", "login_challenge_requires_live_session");
      }
      CrossDeviceApprovalService.Challenge challenge = this.approvals.create(
          UUID.fromString(rawPlayerId), username, action);
      return Map.of(
          "ok", true,
          "action", action.name(),
          "expiresAt", challenge.expiresAt().toString(),
          "url", this.websiteOrigin + "/minecraft/approve?token=" + challenge.token()
              + "&action=" + action.name().toLowerCase(Locale.ROOT),
          "token", challenge.token());
    } catch (IllegalArgumentException error) {
      return Map.of("ok", false, "error", "invalid_request");
    }
  }

  private static Request parse(JsonHttpExchange ctx) {
    try {
      Request request = ctx.bodyAsClass(Request.class);
      if (request == null) throw new IllegalArgumentException("request is required");
      return request;
    } catch (Exception error) {
      throw new IllegalArgumentException("invalid request", error);
    }
  }

  private static CrossDeviceApprovalService.Action action(String raw) {
    if (raw == null || raw.isBlank()) throw new IllegalArgumentException("action is required");
    return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
      case "bind_email" -> CrossDeviceApprovalService.Action.BIND_EMAIL;
      case "enable_totp" -> CrossDeviceApprovalService.Action.ENABLE_TOTP;
      case "bind_skin_account" -> CrossDeviceApprovalService.Action.BIND_SKIN_ACCOUNT;
      case "approve_login" -> CrossDeviceApprovalService.Action.APPROVE_LOGIN;
      default -> throw new IllegalArgumentException("unsupported action");
    };
  }

  private static String normalizeOrigin(String origin) {
    if (origin == null || origin.isBlank()) throw new IllegalArgumentException("website origin is required");
    return origin.trim().replaceAll("/+$", "");
  }

  private static final class Request {
    private String playerId;
    private String username;
    private String action;
    private String token;
    private String email;
  }

  @FunctionalInterface
  public interface ApprovalExecutor {
    boolean execute(CrossDeviceApprovalService.Challenge challenge, String email);
  }
}
