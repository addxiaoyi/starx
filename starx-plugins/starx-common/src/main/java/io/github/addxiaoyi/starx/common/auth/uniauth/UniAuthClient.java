package io.github.addxiaoyi.starx.common.auth.uniauth;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class UniAuthClient {
  private static final Logger LOGGER = Logger.getLogger(UniAuthClient.class.getName());
  private static final Gson GSON = new Gson();

  private final UniAuthConfig config;
  private final HttpClient httpClient;
  private volatile String publicKey;

  public UniAuthClient(UniAuthConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.timeoutMs()))
        .build();
  }

  private String getPublicKey() {
    String current = publicKey;
    if (current == null) {
      synchronized (this) {
        current = publicKey;
        if (current == null) {
          refreshPublicKey();
          current = publicKey;
        }
      }
    }
    return current;
  }

  private void refreshPublicKey() {
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(config.apiUrl() + "publickey"))
          .timeout(Duration.ofMillis(config.timeoutMs()))
          .GET()
          .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "Failed to fetch public key: HTTP " + response.statusCode());
      }
      String fetched = response.body().trim();
      if (fetched.isBlank()) {
        throw new IllegalStateException("UniAuth returned an empty public key");
      }
      publicKey = fetched;
      LOGGER.log(Level.FINE, "UniAuth public key fetched");
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to fetch UniAuth public key", exception);
    }
  }

  private JsonObject request(String endpoint, Map<String, String> data) {
    RuntimeException firstFailure;
    try {
      return sendRequest(endpoint, data);
    } catch (RuntimeException exception) {
      if (isIntegrityFailure(exception)) {
        throw exception;
      }
      firstFailure = exception;
    }

    synchronized (this) {
      publicKey = null;
    }
    try {
      return sendRequest(endpoint, data);
    } catch (RuntimeException retryFailure) {
      retryFailure.addSuppressed(firstFailure);
      throw new IllegalStateException("UniAuth request failed: " + endpoint, retryFailure);
    }
  }

  private JsonObject sendRequest(String endpoint, Map<String, String> data) {
    try {
      HashMap<String, Object> payload = new HashMap<>();
      payload.put("data", data);
      payload.put("apikey", config.apiKey());
      payload.put("timestamp", System.currentTimeMillis());
      String encrypted = RSAUtil.encryptByPublicKey(GSON.toJson(payload), getPublicKey());
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(config.apiUrl() + endpoint))
          .timeout(Duration.ofMillis(config.timeoutMs()))
          .header("Content-Type", "text/plain; charset=utf-8")
          .POST(HttpRequest.BodyPublishers.ofString(encrypted, StandardCharsets.UTF_8))
          .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "UniAuth returned HTTP " + response.statusCode() + " for " + endpoint);
      }
      verifyChecksum(response);
      JsonElement parsed = JsonParser.parseString(response.body());
      if (!parsed.isJsonObject()) {
        throw new IllegalStateException("UniAuth returned a non-object response");
      }
      return parsed.getAsJsonObject();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("UniAuth request interrupted", exception);
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("UniAuth request failed: " + endpoint, exception);
    }
  }

  private void verifyChecksum(HttpResponse<String> response) {
    String checksum = response.headers().firstValue("X-Checksum").orElse("");
    String timestamp = response.headers().firstValue("X-Timestamp").orElse("");
    if (checksum.isEmpty() && timestamp.isEmpty()) {
      return;
    }
    if (checksum.isEmpty() || timestamp.isEmpty()) {
      throw new IllegalStateException("UniAuth response checksum headers are incomplete");
    }
    String expected = sha256(response.body()) + "$" + timestamp;
    String actual;
    try {
      actual = RSAUtil.decryptByPublicKey(checksum, getPublicKey());
    } catch (Exception exception) {
      throw new IllegalStateException("UniAuth response checksum verification failed", exception);
    }
    if (!expected.equals(actual)) {
      throw new IllegalStateException("UniAuth response checksum mismatch");
    }
  }

  private static boolean isIntegrityFailure(RuntimeException exception) {
    String message = exception.getMessage();
    return message != null && message.toLowerCase().contains("checksum");
  }

  public CompletableFuture<LoginResponse> login(String username, String password) {
    try {
      JsonObject json = request("login", Map.of(
          "username", Objects.requireNonNullElse(username, ""),
          "password", Objects.requireNonNullElse(password, "")));
      return CompletableFuture.completedFuture(parseLoginResponse(json));
    } catch (Exception exception) {
      LOGGER.log(Level.WARNING, "UniAuth login failed: {0}", safeMessage(exception));
      return CompletableFuture.completedFuture(
          new LoginResponse(false, safeMessage(exception), null, null, false));
    }
  }

  static LoginResponse parseLoginResponse(JsonObject json) {
    int code = integer(json, "code", 500);
    if (code == 200) {
      PlayerProfileResponse profile = parseProfileResponse(json);
      return new LoginResponse(
          true, "登录成功", profile.externalUserId(), profile.email(), false);
    }
    if (code == 403) {
      return new LoginResponse(true, "邮箱未验证，已转为本地认证", null, null, true);
    }
    String message = switch (code) {
      case 401 -> "密码错误";
      case 402 -> "用户未注册";
      default -> "认证失败: " + code;
    };
    return new LoginResponse(false, message, null, null, false);
  }

  public CompletableFuture<PlayerProfileResponse> fetchProfile(String username) {
    try {
      JsonObject json = request("playerInfo", Map.of(
          "username", Objects.requireNonNullElse(username, "")));
      return CompletableFuture.completedFuture(parseProfileResponse(json));
    } catch (Exception exception) {
      LOGGER.log(Level.WARNING, "UniAuth profile request failed: {0}", safeMessage(exception));
      return CompletableFuture.completedFuture(PlayerProfileResponse.error());
    }
  }

  public CompletableFuture<StatusResponse> fetchStatus(String username) {
    return fetchProfile(username).thenApply(profile -> new StatusResponse(
        profile.exists(),
        profile.registered(),
        profile.status()));
  }

  static PlayerProfileResponse parseProfileResponse(JsonObject json) {
    int code = integer(json, "code", 500);
    if (code != 200) {
      return new PlayerProfileResponse(
          false, false, false, null, null, null, null, "ERROR");
    }

    JsonObject data = object(json, "data");
    boolean exists = firstBoolean(data, false,
        "profile.exists", "exists", "player.exists");
    boolean registered = firstBoolean(data, false,
        "profile.registered", "registered", "player.registered");
    String username = firstString(data,
        "profile.username", "profile.name", "player.username", "player.name",
        "username", "name");
    String uuid = firstString(data,
        "profile.uuid", "player.uuid", "minecraftUuid", "minecraft_uuid", "uuid");
    String externalUserId = firstString(data,
        "profile.externalUserId", "profile.external_user_id",
        "profile.userId", "profile.user_id", "profile.uid", "profile.id",
        "user.externalUserId", "user.external_user_id",
        "user.userId", "user.user_id", "user.uid", "user.id",
        "externalUserId", "external_user_id", "userId", "user_id", "uid", "id");
    String email = firstString(data,
        "profile.email", "user.email", "email", "mail");
    String status = registered ? "REGISTERED" : (exists ? "IMPORTED" : "NOT_EXIST");
    return new PlayerProfileResponse(
        true, exists, registered, username, uuid, externalUserId, email, status);
  }

  private static JsonObject object(JsonObject root, String path) {
    JsonElement element = element(root, path);
    return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
  }

  private static String firstString(JsonObject root, String... paths) {
    for (String path : paths) {
      JsonElement element = element(root, path);
      if (element == null || element.isJsonNull() || element.isJsonObject()
          || element.isJsonArray()) {
        continue;
      }
      try {
        String value = element.getAsString().trim();
        if (!value.isBlank()) {
          return value;
        }
      } catch (RuntimeException ignored) {
        // Try the next compatible field.
      }
    }
    return null;
  }

  private static boolean firstBoolean(JsonObject root, boolean fallback, String... paths) {
    for (String path : paths) {
      JsonElement element = element(root, path);
      if (element == null || element.isJsonNull() || element.isJsonObject()
          || element.isJsonArray()) {
        continue;
      }
      try {
        return element.getAsBoolean();
      } catch (RuntimeException ignored) {
        // Try the next compatible field.
      }
    }
    return fallback;
  }

  private static JsonElement element(JsonObject root, String path) {
    if (root == null || path == null || path.isBlank()) {
      return null;
    }
    if (root.has(path)) {
      return root.get(path);
    }
    JsonElement current = root;
    for (String part : path.split("\\.")) {
      if (current == null || !current.isJsonObject()) {
        return null;
      }
      JsonObject object = current.getAsJsonObject();
      if (!object.has(part)) {
        return null;
      }
      current = object.get(part);
    }
    return current;
  }

  private static int integer(JsonObject root, String key, int fallback) {
    JsonElement element = root == null ? null : root.get(key);
    if (element == null || element.isJsonNull()) {
      return fallback;
    }
    try {
      return element.getAsInt();
    } catch (RuntimeException ignored) {
      return fallback;
    }
  }

  private static String safeMessage(Throwable throwable) {
    String message = throwable == null ? null : throwable.getMessage();
    if (message == null || message.isBlank()) {
      return "UniAuth request failed";
    }
    return message.length() > 240 ? message.substring(0, 240) : message;
  }

  private static String sha256(String data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        result.append(String.format("%02x", value));
      }
      return result.toString();
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 not available", exception);
    }
  }

  public record LoginResponse(
      boolean success,
      String message,
      String userId,
      String email,
      boolean requiresLocalMigration) {}

  public record StatusResponse(boolean exists, boolean imported, String status) {}

  public record PlayerProfileResponse(
      boolean success,
      boolean exists,
      boolean registered,
      String username,
      String uuid,
      String externalUserId,
      String email,
      String status) {
    static PlayerProfileResponse error() {
      return new PlayerProfileResponse(
          false, false, false, null, null, null, null, "ERROR");
    }
  }
}
