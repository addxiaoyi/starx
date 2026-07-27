package io.github.addxiaoyi.starx.server;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class BackendHeartbeatClient {
  static final String SERVER_HEADER = "X-StarX-Server";
  private static final String API_KEY_HEADER = "X-API-Key";
  private static final String SERVER_PATTERN = "[A-Za-z0-9_.-]{1,64}";

  private final HttpClient http;
  private final URI velocityUrl;
  private final String apiKey;
  private final String serverName;
  private final Duration timeout;

  BackendHeartbeatClient(
      URI velocityUrl,
      String apiKey,
      String serverName,
      Duration timeout
  ) {
    this.velocityUrl = requireHttpUrl(velocityUrl);
    this.apiKey = requireText(apiKey, "heartbeat API key");
    this.serverName = requireServerName(serverName);
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("heartbeat timeout must be between 1 ms and 60 seconds");
    }
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  CompletableFuture<Optional<BridgeMessage>> send(BridgeMessage status) {
    HttpRequest request = buildRequest(
        this.velocityUrl, this.apiKey, this.serverName, status, this.timeout);
    return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII))
        .thenApply(response -> decodeCommandResponse(response.statusCode(), response.body()));
  }

  static HttpRequest buildRequest(
      URI velocityUrl,
      String apiKey,
      String serverName,
      BridgeMessage status,
      Duration timeout
  ) {
    URI base = requireHttpUrl(velocityUrl);
    String key = requireText(apiKey, "heartbeat API key");
    String registeredServer = requireServerName(serverName);
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(timeout, "timeout");
    URI endpoint = base.resolve("/v1/backend/heartbeat");
    return HttpRequest.newBuilder(endpoint)
        .timeout(timeout)
        .header(API_KEY_HEADER, key)
        .header(SERVER_HEADER, registeredServer)
        .header("Content-Type", "text/plain; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(encodeBody(status), StandardCharsets.US_ASCII))
        .build();
  }

  static String encodeBody(BridgeMessage message) {
    return Base64.getEncoder().encodeToString(BridgeProtocol.encode(message));
  }

  static byte[] decodeBody(String body) {
    return Base64.getDecoder().decode(requireText(body, "heartbeat body"));
  }

  static Optional<BridgeMessage> decodeCommandResponse(int statusCode, String body) {
    if (statusCode < 200 || statusCode >= 300) {
      throw new IllegalStateException("Velocity heartbeat returned HTTP " + statusCode);
    }
    if (body == null || body.isBlank()) {
      return Optional.empty();
    }
    BridgeMessage command = BridgeProtocol.decode(decodeBody(body));
    boolean supported = BridgeProtocol.PROXY_HELLO.equals(command.type())
        || BridgeProtocol.STATUS_REQUEST.equals(command.type())
        || BridgeProtocol.SKIN_REQUEST.equals(command.type())
        || BridgeProtocol.CONFIG_SYNC.equals(command.type());
    if (command.platform() != io.github.addxiaoyi.starx.api.bridge.PlatformKind.VELOCITY
        || !supported) {
      throw new IllegalArgumentException(
          "Unsupported Velocity heartbeat command: " + command.type());
    }
    return Optional.of(command);
  }

  private static URI requireHttpUrl(URI uri) {
    Objects.requireNonNull(uri, "velocityUrl");
    String scheme = uri.getScheme();
    boolean isHttp = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    if (!isHttp || uri.getHost() == null || uri.getUserInfo() != null
        || uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("velocity-url must be an HTTP(S) origin without credentials");
    }
    return uri;
  }

  private static String requireServerName(String value) {
    String name = requireText(value, "server name");
    if (!name.matches(SERVER_PATTERN)) {
      throw new IllegalArgumentException("server name must match " + SERVER_PATTERN);
    }
    return name;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value.trim();
  }
}
