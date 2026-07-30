package io.github.addxiaoyi.starx.velocity.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Bounded semantic probe for the public StarX health endpoint used by managed FRP. */
final class StarxHealthProbe {
  private static final int MAX_RESPONSE_BYTES = 16 * 1024;

  private StarxHealthProbe() {
  }

  static Result probe(String host, int port, Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    String endpoint;
    try {
      endpoint = endpoint(host, port);
    } catch (RuntimeException invalidEndpoint) {
      return new Result(
          Status.INVALID_ENDPOINT,
          "",
          0,
          safeMessage(invalidEndpoint));
    }

    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
      int timeoutMillis = timeoutMillis(timeout);
      connection.setConnectTimeout(timeoutMillis);
      connection.setReadTimeout(timeoutMillis);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("GET");
      connection.setRequestProperty("Accept", "application/json");
      connection.setUseCaches(false);

      int status = connection.getResponseCode();
      if (status != 200) {
        return new Result(
            Status.HTTP_STATUS_REJECTED,
            endpoint,
            status,
            "expected HTTP 200");
      }

      byte[] body;
      try (InputStream input = connection.getInputStream()) {
        body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
      }
      if (body.length > MAX_RESPONSE_BYTES) {
        return new Result(
            Status.RESPONSE_TOO_LARGE,
            endpoint,
            status,
            "health response exceeds " + MAX_RESPONSE_BYTES + " bytes");
      }

      JsonObject root;
      try {
        root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
            .getAsJsonObject();
      } catch (RuntimeException invalidJson) {
        return new Result(
            Status.INVALID_JSON,
            endpoint,
            status,
            safeMessage(invalidJson));
      }
      if (!root.has("status")
          || !root.get("status").isJsonPrimitive()
          || !"ok".equals(root.get("status").getAsString().trim().toLowerCase(Locale.ROOT))) {
        return new Result(
            Status.INVALID_STATUS,
            endpoint,
            status,
            "health payload status is not ok");
      }
      return new Result(Status.HEALTHY, endpoint, status, "");
    } catch (SocketTimeoutException timeoutError) {
      return new Result(Status.TIMEOUT, endpoint, 0, safeMessage(timeoutError));
    } catch (IOException connectionError) {
      return new Result(Status.CONNECTION_FAILED, endpoint, 0, safeMessage(connectionError));
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  static Result synthetic(String host, int port, boolean healthy) {
    String endpoint = endpoint(host, port);
    return healthy
        ? new Result(Status.HEALTHY, endpoint, 200, "injected probe")
        : new Result(Status.CONNECTION_FAILED, endpoint, 0, "injected probe");
  }

  static String endpoint(String host, int port) {
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("health probe port must be between 1 and 65535");
    }
    String normalized = normalizeHost(host);
    String uriHost = normalized.contains(":") ? "[" + normalized + "]" : normalized;
    return "http://" + uriHost + ":" + port + "/v1/health";
  }

  private static String normalizeHost(String host) {
    String normalized = Objects.requireNonNullElse(host, "").trim();
    if (normalized.startsWith("[") && normalized.endsWith("]")) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    if (normalized.isBlank()
        || "0.0.0.0".equals(normalized)
        || "::".equals(normalized)) {
      return "127.0.0.1";
    }
    if (normalized.contains("/")
        || normalized.contains("?")
        || normalized.contains("#")
        || normalized.contains("@")) {
      throw new IllegalArgumentException("invalid health probe host");
    }
    return normalized;
  }

  private static int timeoutMillis(Duration timeout) {
    long millis = timeout.toMillis();
    if (millis < 1) {
      return 1;
    }
    return (int) Math.min(Integer.MAX_VALUE, millis);
  }

  private static String safeMessage(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank()
        ? error.getClass().getSimpleName()
        : message;
  }

  enum Status {
    HEALTHY,
    INVALID_ENDPOINT,
    CONNECTION_FAILED,
    TIMEOUT,
    HTTP_STATUS_REJECTED,
    RESPONSE_TOO_LARGE,
    INVALID_JSON,
    INVALID_STATUS
  }

  record Result(Status status, String endpoint, int httpStatus, String diagnostic) {
    Result {
      status = Objects.requireNonNull(status, "status");
      endpoint = Objects.requireNonNullElse(endpoint, "");
      diagnostic = Objects.requireNonNullElse(diagnostic, "");
      if (httpStatus < 0 || httpStatus > 999) {
        throw new IllegalArgumentException("invalid HTTP status");
      }
    }

    boolean healthy() {
      return this.status == Status.HEALTHY;
    }
  }
}
