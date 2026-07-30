package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;
import io.github.addxiaoyi.starx.velocity.http.RouteRegistrar;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated, side-effect-free website-to-plugin connectivity probe.
 *
 * <p>This handler only validates and acknowledges a bounded message. It never executes console
 * commands, mutates players, or changes server state.</p>
 */
public final class BridgePingHandler implements AdminHandler {
  private static final int MAX_MESSAGE_CHARS = 256;
  private final Clock clock;

  public BridgePingHandler(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void register(RouteRegistrar routes, RouteRegistrar.RouteHandler... authFilter) {
    routes.post("/v1/admin/bridge/ping", chain(this::handle, authFilter));
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
    PingRequest request;
    try {
      request = ctx.bodyAsClass(PingRequest.class);
    } catch (Exception error) {
      ctx.status(400).json(Map.of(
          "ok", false,
          "success", false,
          "error", "invalid_json"));
      return;
    }

    PingAck ack;
    try {
      ack = acknowledge(
          request == null ? null : request.nonce,
          request == null ? null : request.message,
          request == null ? null : request.sentAt,
          this.clock);
    } catch (IllegalArgumentException error) {
      ctx.status(400).json(Map.of(
          "ok", false,
          "success", false,
          "error", error.getMessage()));
      return;
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", true);
    body.put("success", true);
    body.put("nonce", ack.nonce());
    body.put("message", ack.message());
    body.put("sentAt", ack.sentAt());
    body.put("receivedAt", ack.receivedAt());
    body.put("service", "starx-velocity");
    body.put("sideEffectFree", true);
    ctx.status(200).json(body);
  }

  static PingAck acknowledge(
      String nonceValue,
      String messageValue,
      String sentAtValue,
      Clock clock
  ) {
    Objects.requireNonNull(clock, "clock");
    String nonce = Objects.requireNonNullElse(nonceValue, "").trim();
    try {
      nonce = UUID.fromString(nonce).toString();
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("invalid_nonce");
    }

    String message = Objects.requireNonNullElse(messageValue, "").trim();
    if (message.length() > MAX_MESSAGE_CHARS || containsControlCharacter(message)) {
      throw new IllegalArgumentException("invalid_message");
    }

    String sentAt = Objects.requireNonNullElse(sentAtValue, "").trim();
    if (!sentAt.isEmpty()) {
      try {
        sentAt = Instant.parse(sentAt).toString();
      } catch (DateTimeParseException error) {
        throw new IllegalArgumentException("invalid_sent_at");
      }
    }
    return new PingAck(nonce, message, sentAt, clock.instant().toString());
  }

  private static boolean containsControlCharacter(String value) {
    return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint));
  }

  record PingAck(String nonce, String message, String sentAt, String receivedAt) {
  }

  private static final class PingRequest {
    private String nonce;
    private String message;
    private String sentAt;
  }
}
