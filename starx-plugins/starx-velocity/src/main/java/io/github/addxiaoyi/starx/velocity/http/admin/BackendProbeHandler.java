package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.velocity.bridge.BackendCommandMailbox;
import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class BackendProbeHandler implements AdminHandler {
  private static final String SERVER_PATTERN = "[A-Za-z0-9_.-]{1,64}";

  private final Predicate<String> knownServer;
  private final BackendCommandMailbox mailbox;

  public BackendProbeHandler(
      Predicate<String> knownServer,
      BackendCommandMailbox mailbox
  ) {
    this.knownServer = Objects.requireNonNull(knownServer, "knownServer");
    this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
  }

  @Override
  public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... authFilter) {
    routes.post("/v1/admin/backend/probe", this.chain(this::handle, authFilter));
  }

  private RouteRegistrar.RouteHandler chain(
      RouteRegistrar.RouteHandler handler,
      RouteRegistrar.RouteHandler... authFilter
  ) {
    return ctx -> {
      for (RouteRegistrar.RouteHandler filter : authFilter) {
        filter.handle(ctx);
      }
      handler.handle(ctx);
    };
  }

  private void handle(JsonHttpExchange ctx) throws IOException {
    ProbeRequest request;
    try {
      request = ctx.bodyAsClass(ProbeRequest.class);
    } catch (Exception error) {
      ctx.status(400).json(Map.of("ok", false, "error", "Invalid JSON request body"));
      return;
    }

    ProbeResult result = enqueue(
        request == null ? null : request.server,
        this.knownServer,
        this.mailbox,
        () -> UUID.randomUUID().toString());
    switch (result.status()) {
      case QUEUED -> ctx.status(202).json(Map.of(
          "ok", true,
          "data", Map.of(
              "server", result.server(),
              "correlationId", result.correlationId(),
              "transport", "heartbeat-http",
              "queued", true)));
      case INVALID_SERVER -> ctx.status(400).json(Map.of(
          "ok", false,
          "error", "server must match " + SERVER_PATTERN));
      case UNKNOWN_SERVER -> ctx.status(404).json(Map.of(
          "ok", false,
          "error", "Velocity backend is not registered: " + result.server()));
      case MAILBOX_FULL -> ctx.status(503).json(Map.of(
          "ok", false,
          "error", "Backend command mailbox is full: " + result.server()));
    }
  }

  static ProbeResult enqueue(
      String serverName,
      Predicate<String> knownServer,
      BackendCommandMailbox mailbox,
      Supplier<String> correlationIds
  ) {
    Objects.requireNonNull(knownServer, "knownServer");
    Objects.requireNonNull(mailbox, "mailbox");
    Objects.requireNonNull(correlationIds, "correlationIds");
    String server = serverName == null ? "" : serverName.trim();
    if (!server.matches(SERVER_PATTERN)) {
      return new ProbeResult(ProbeStatus.INVALID_SERVER, server, "");
    }
    if (!knownServer.test(server)) {
      return new ProbeResult(ProbeStatus.UNKNOWN_SERVER, server, "");
    }
    String correlationId = Objects.requireNonNull(
        correlationIds.get(), "correlationId").trim();
    if (correlationId.isEmpty()) {
      throw new IllegalStateException("Probe correlation id must not be blank");
    }
    boolean queued = mailbox.offer(
        server, BridgeMessage.statusRequest("proxy", correlationId));
    ProbeStatus status = queued ? ProbeStatus.QUEUED : ProbeStatus.MAILBOX_FULL;
    return new ProbeResult(status, server, correlationId);
  }

  enum ProbeStatus {
    QUEUED,
    INVALID_SERVER,
    UNKNOWN_SERVER,
    MAILBOX_FULL
  }

  record ProbeResult(ProbeStatus status, String server, String correlationId) {
  }

  private static final class ProbeRequest {
    private String server;
  }
}
