package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.account.JdbcAccountDeletionRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

public final class AccountDeletionHandler implements AdminHandler {
  private static final Duration COOLING_OFF = Duration.ofDays(7);
  private final JdbcAccountDeletionRepository deletions;
  private final JdbcUserRepository users;
  private final Clock clock;

  public AccountDeletionHandler(JdbcAccountDeletionRepository deletions, JdbcUserRepository users) {
    this(deletions, users, Clock.systemUTC());
  }

  AccountDeletionHandler(JdbcAccountDeletionRepository deletions, JdbcUserRepository users, Clock clock) {
    this.deletions = Objects.requireNonNull(deletions, "deletions");
    this.users = Objects.requireNonNull(users, "users");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... authFilters) {
    routes.get("/v1/user/deletion/status", chain(this::status, authFilters));
    routes.post("/v1/user/deletion/request", chain(this::request, authFilters));
    routes.post("/v1/user/deletion/cancel", chain(this::cancel, authFilters));
  }

  private void status(JsonHttpExchange context) throws IOException {
    String rawUuid = context.queryParam("playerUuid");
    UUID playerUuid;
    try {
      playerUuid = UUID.fromString(rawUuid == null ? "" : rawUuid.trim());
    } catch (IllegalArgumentException error) {
      context.status(400).json(Map.of("ok", false, "error", "valid playerUuid is required"));
      return;
    }
    var latest = deletions.latest(playerUuid);
    if (latest.isEmpty()) {
      context.status(200).json(Map.of("ok", true, "requested", false));
      return;
    }
    JdbcAccountDeletionRepository.RequestStatus request = latest.get();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("requested", true);
    payload.put("requestId", request.requestId());
    payload.put("state", request.state());
    payload.put("requestedAt", request.requestedAt());
    payload.put("executeAfter", request.executeAfter());
    if (request.cancelledAt() != null) payload.put("cancelledAt", request.cancelledAt());
    if (request.claimedAt() != null) payload.put("claimedAt", request.claimedAt());
    if (request.completedAt() != null) payload.put("completedAt", request.completedAt());
    context.status(200).json(payload);
  }

  private RouteRegistrar.RouteHandler chain(RouteRegistrar.RouteHandler handler,
                                             RouteRegistrar.RouteHandler... filters) {
    return context -> {
      for (RouteRegistrar.RouteHandler filter : filters) filter.handle(context);
      handler.handle(context);
    };
  }

  private void request(JsonHttpExchange context) throws Exception {
    PlayerRequest request = context.bodyAsClass(PlayerRequest.class);
    if (request.playerUuid == null || !users.existsByUuid(request.playerUuid)) {
      context.status(404).json(Map.of("ok", false, "error", "player_not_found"));
      return;
    }
    long now = clock.millis();
    long executeAfter = now + COOLING_OFF.toMillis();
    String requestId = deletions.request(request.playerUuid, now, executeAfter);
    context.status(202).json(Map.of(
        "ok", true,
        "requestId", requestId,
        "executeAfter", executeAfter,
        "coolingOffDays", 7));
  }

  private void cancel(JsonHttpExchange context) throws Exception {
    CancelRequest request = context.bodyAsClass(CancelRequest.class);
    if (request.requestId == null || request.requestId.isBlank() || request.playerUuid == null) {
      context.status(400).json(Map.of("ok", false, "error", "requestId and playerUuid are required"));
      return;
    }
    context.status(200).json(Map.of(
        "ok", true,
        "changed", deletions.cancel(request.requestId.trim(), request.playerUuid, clock.millis())));
  }

  static final class PlayerRequest { public UUID playerUuid; PlayerRequest() {} }
  static final class CancelRequest {
    public String requestId;
    public UUID playerUuid;
    CancelRequest() {}
  }
}
