package io.github.addxiaoyi.starx.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class StarxAccountClient {
  private static final Gson JSON = new Gson();
  private static final int MAX_RESPONSE_BYTES = 262_144;

  private final HttpClient http;
  private final URI baseUrl;
  private final String apiKey;

  StarxAccountClient(String baseUrl, String apiKey) {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build(), baseUrl, apiKey);
  }

  StarxAccountClient(HttpClient http, String baseUrl, String apiKey) {
    this.http = Objects.requireNonNull(http, "http");
    this.baseUrl = URI.create(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.apiKey = Objects.requireNonNullElse(apiKey, "").trim();
  }

  CompletableFuture<Reply> sendEmailChallenge(UUID playerId, String username, String email) {
    JsonObject body = new JsonObject();
    body.addProperty("uuid", playerId.toString());
    body.addProperty("username", username);
    body.addProperty("email", email);
    return this.post("/v1/admin/email-challenge/send", body);
  }

  CompletableFuture<Reply> confirmEmail(UUID playerId, String username, String code) {
    JsonObject body = new JsonObject();
    body.addProperty("uuid", playerId.toString());
    body.addProperty("username", username);
    body.addProperty("code", code);
    return this.post("/v1/admin/email-challenge/confirm", body);
  }

  CompletableFuture<Reply> enableTotp(UUID playerId, String password) {
    JsonObject body = new JsonObject();
    body.addProperty("uuid", playerId.toString());
    body.addProperty("password", password);
    return this.post("/v1/admin/totp/setup", body);
  }

  CompletableFuture<Reply> confirmTotp(UUID playerId, String code) {
    return this.totpPost("/v1/admin/totp/confirm", playerId, "code", code);
  }

  CompletableFuture<Reply> disableTotp(UUID playerId, String password) {
    return this.totpPost("/v1/admin/totp/disable", playerId, "password", password);
  }

  CompletableFuture<Reply> rotateRecoveryCodes(UUID playerId, String code) {
    return this.totpPost("/v1/admin/totp/recovery-codes/rotate", playerId, "code", code);
  }

  CompletableFuture<Reply> createApproval(UUID playerId, String username, String action) {
    JsonObject body = new JsonObject();
    body.addProperty("playerId", playerId.toString());
    body.addProperty("username", username);
    body.addProperty("action", action);
    return this.post("/v1/admin/approval/create", body);
  }

  private CompletableFuture<Reply> totpPost(
      String path, UUID playerId, String field, String value) {
    JsonObject body = new JsonObject();
    body.addProperty("uuid", playerId.toString());
    body.addProperty(field, value);
    return this.post(path, body);
  }

  private CompletableFuture<Reply> post(String path, JsonObject body) {
    HttpRequest request = HttpRequest.newBuilder(this.baseUrl.resolve(path))
        .timeout(Duration.ofSeconds(8))
        .header("Content-Type", "application/json")
        .header("X-API-Key", this.apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body)))
        .build();
    return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        .thenApply(response -> parse(response.statusCode(), readResponse(response.body())));
  }

  private static String readResponse(InputStream input) {
    try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int total = 0;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > MAX_RESPONSE_BYTES) {
          throw new IllegalStateException("StarX API response is too large");
        }
        output.write(buffer, 0, read);
      }
      return output.toString(StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to read StarX API response", error);
    }
  }

  private static Reply parse(int status, String body) {
    try {
      JsonObject json = JSON.fromJson(body, JsonObject.class);
      if (status >= 200 && status < 300) {
        String message = json.has("message") ? json.get("message").getAsString() : "操作成功";
        String secret = json.has("secret") ? json.get("secret").getAsString() : "";
        String otpauthUri = json.has("otpauthUri") ? json.get("otpauthUri").getAsString() : "";
        String url = json.has("url") ? json.get("url").getAsString() : "";
        String action = json.has("action") ? json.get("action").getAsString() : "";
        return new Reply(true, message, secret, otpauthUri, url, action);
      }
      String error = json != null && json.has("error")
          ? json.get("error").getAsString() : "StarX API 返回 " + status;
      return new Reply(false, error, "", "", "", "");
    } catch (RuntimeException error) {
      throw new IllegalStateException("StarX API returned malformed JSON (HTTP " + status + ")", error);
    }
  }

  record Reply(boolean ok, String message, String secret, String otpauthUri, String url, String action) {
  }
}
