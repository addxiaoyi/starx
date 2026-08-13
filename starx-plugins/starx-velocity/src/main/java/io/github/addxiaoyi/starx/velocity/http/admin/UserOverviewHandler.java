package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.database.JdbcBindingRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.common.session.JdbcPlayerSessionRepository;
import io.github.addxiaoyi.starx.common.session.PlayerSessionSummary;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class UserOverviewHandler implements AdminHandler {
  private final JdbcUserRepository users;
  private final JdbcBindingRepository bindings;
  private final JdbcPlayerSessionRepository sessions;

  public UserOverviewHandler(JdbcUserRepository users, JdbcBindingRepository bindings,
                             JdbcPlayerSessionRepository sessions) {
    this.users = Objects.requireNonNull(users, "users");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
  }

  @Override public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... authFilter) {
    routes.get("/v1/user/overview", chain(this::overview, authFilter));
  }

  private RouteRegistrar.RouteHandler chain(RouteRegistrar.RouteHandler handler,
                                             RouteRegistrar.RouteHandler... filters) {
    return context -> {
      for (RouteRegistrar.RouteHandler filter : filters) filter.handle(context);
      handler.handle(context);
    };
  }

  private void overview(JsonHttpExchange context) throws IOException {
    String name = context.queryParam("name");
    if (name == null || name.isBlank() || name.length() > 16) {
      context.status(400).json(Map.of("ok", false, "error", "valid name is required"));
      return;
    }
    StarxUser user = users.findFullByUsername(name).orElse(null);
    if (user == null) {
      context.status(404).json(Map.of("ok", false, "error", "user_not_found"));
      return;
    }

    PlayerBinding binding = bindings.findByPlayer(user.uuid()).orElse(null);
    PlayerSessionSummary session = sessions.summary(user.uuid()).orElse(null);
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("uuid", user.uuid().toString());
    identity.put("username", user.username());
    identity.put("premium", user.premium());
    identity.put("source", value(user.sourceSystem()));
    identity.put("createdAt", user.createdAt() == null ? "" : user.createdAt().toString());

    Map<String, Object> security = new LinkedHashMap<>();
    security.put("emailBound", user.email() != null && !user.email().isBlank());
    security.put("totpEnabled", user.totpSecret() != null && !user.totpSecret().isBlank());
    security.put("trustedDeviceCount", user.trustedDevices().size());
    security.put("lastLoginAt", user.lastLoginAt() == null ? "" : user.lastLoginAt().toString());
    security.put("lastLoginIp", value(user.lastLoginIp()));
    security.put("lastLoginLocation", value(user.lastLoginLocation()));

    Map<String, Object> linked = new LinkedHashMap<>();
    linked.put("qqBound", binding != null && binding.qqId() != null && !binding.qqId().isBlank());
    linked.put("discordBound", binding != null && binding.discordId() != null && !binding.discordId().isBlank());
    linked.put("websiteBound", users.hasTrustedWebsiteBinding(user.uuid(), user.username()));

    Map<String, Object> play = new LinkedHashMap<>();
    play.put("totalMillis", session == null ? 0L : session.totalPlaytime());
    play.put("loginCount", session == null ? 0 : session.loginCount());
    play.put("lastServer", session == null ? "" : value(session.lastServer()));
    play.put("lastDisconnect", session == null ? "UNKNOWN" : session.disconnectReason().name());
    play.put("byServerMillis", sessions.playtimeByServer(user.uuid()));

    context.status(200).json(Map.of(
        "ok", true,
        "identity", identity,
        "security", security,
        "bindings", linked,
        "playtime", play));
  }

  private static String value(String value) { return value == null ? "" : value; }
}
