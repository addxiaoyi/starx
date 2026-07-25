package io.github.addxiaoyi.starx.velocity.http;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.velocity.bridge.BackendCommandMailbox;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import java.io.IOException;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class BackendHeartbeatHandler {
  static final String SERVER_HEADER = "X-StarX-Server";
  private static final String SERVER_PATTERN = "[A-Za-z0-9_.-]{1,64}";
  private static final int MAX_BASE64_BYTES = 43_688;

  private final BackendNodeRegistry registry;
  private final Predicate<String> knownServer;
  private final Clock clock;
  private final BackendCommandMailbox mailbox;
  private final Consumer<BridgeMessage> messageConsumer;

  BackendHeartbeatHandler(
      BackendNodeRegistry registry,
      Predicate<String> knownServer,
      Clock clock
  ) {
    this(registry, knownServer, clock, new BackendCommandMailbox(1), ignored -> { });
  }

  BackendHeartbeatHandler(
      BackendNodeRegistry registry,
      Predicate<String> knownServer,
      Clock clock,
      BackendCommandMailbox mailbox,
      Consumer<BridgeMessage> messageConsumer
  ) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.knownServer = Objects.requireNonNull(knownServer, "knownServer");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
    this.messageConsumer = Objects.requireNonNull(messageConsumer, "messageConsumer");
  }

  void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... auth) {
    routes.post("/v1/backend/heartbeat", ctx -> {
      for (RouteRegistrar.RouteHandler filter : auth) {
        filter.handle(ctx);
      }
      this.handle(ctx);
    });
  }

  private void handle(JsonHttpExchange ctx) throws IOException {
    String serverName = ctx.header(SERVER_HEADER);
    Optional<BridgeMessage> command = exchange(
        serverName,
        ctx.bodyString(),
        this.knownServer,
        this.registry,
        this.mailbox,
        this.messageConsumer,
        this.clock);
    String response = command
        .map(message -> Base64.getEncoder().encodeToString(BridgeProtocol.encode(message)))
        .orElse("");
    ctx.status(200).result(response);
  }

  static Optional<BridgeMessage> exchange(
      String serverName,
      String body,
      Predicate<String> knownServer,
      BackendNodeRegistry registry,
      BackendCommandMailbox mailbox,
      Consumer<BridgeMessage> messageConsumer,
      Clock clock
  ) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(mailbox, "mailbox");
    Objects.requireNonNull(messageConsumer, "messageConsumer");
    Objects.requireNonNull(clock, "clock");
    BridgeMessage message = decodeExchange(serverName, body, knownServer);
    if (BridgeProtocol.STATUS_RESPONSE.equals(message.type())) {
      Optional<BridgeMessage> command = mailbox.poll(serverName);
      registry.update(
          serverName,
          markHeartbeatTransport(message, mailbox.snapshot(serverName)),
          clock.instant());
      return command;
    }
    messageConsumer.accept(message);
    return mailbox.poll(serverName);
  }

  static BridgeMessage decode(
      String serverName,
      String body,
      Predicate<String> knownServer
  ) {
    BridgeMessage message = decodeExchange(serverName, body, knownServer);
    if (!BridgeProtocol.STATUS_RESPONSE.equals(message.type())) {
      throw new IllegalArgumentException("Heartbeat must contain a backend status response");
    }
    return markHeartbeatTransport(message);
  }

  static BridgeMessage decodeExchange(
      String serverName,
      String body,
      Predicate<String> knownServer
  ) {
    Objects.requireNonNull(knownServer, "knownServer");
    if (serverName == null || !serverName.matches(SERVER_PATTERN)) {
      throw new IllegalArgumentException("Invalid StarX backend server name");
    }
    if (!knownServer.test(serverName)) {
      throw new IllegalArgumentException("Unknown Velocity backend server: " + serverName);
    }
    if (body == null || body.isBlank() || body.length() > MAX_BASE64_BYTES) {
      throw new IllegalArgumentException("Invalid StarX heartbeat body size");
    }

    byte[] payload;
    try {
      payload = Base64.getDecoder().decode(body.trim());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("StarX heartbeat body is not valid Base64", error);
    }
    BridgeMessage message = BridgeProtocol.decode(payload);
    if (!serverName.equals(message.nodeId())) {
      throw new IllegalArgumentException("Backend node-id must match its Velocity server name");
    }
    boolean backendPlatform = message.platform() == io.github.addxiaoyi.starx.api.bridge.PlatformKind.PAPER
        || message.platform() == io.github.addxiaoyi.starx.api.bridge.PlatformKind.FOLIA;
    boolean supported = BridgeProtocol.STATUS_RESPONSE.equals(message.type())
        || BridgeProtocol.SKIN_RESPONSE.equals(message.type());
    if (!backendPlatform || !supported) {
      throw new IllegalArgumentException("Unsupported backend exchange message: " + message.type());
    }
    return message;
  }

  private static BridgeMessage markHeartbeatTransport(BridgeMessage message) {
    return markHeartbeatTransport(message, null);
  }

  private static BridgeMessage markHeartbeatTransport(
      BridgeMessage message,
      BackendCommandMailbox.Snapshot snapshot
  ) {
    Map<String, String> attributes = new LinkedHashMap<>(message.attributes());
    attributes.put("transport", "heartbeat-http");
    if (snapshot != null) {
      attributes.put("httpCommandsAccepted", Long.toString(snapshot.accepted()));
      attributes.put("httpCommandsDelivered", Long.toString(snapshot.delivered()));
      attributes.put("httpCommandsRejected", Long.toString(snapshot.rejected()));
      attributes.put("httpCommandsQueued", Integer.toString(snapshot.queued()));
    }
    return new BridgeMessage(
        message.type(),
        message.nodeId(),
        message.platform(),
        message.correlationId(),
        attributes);
  }
}
