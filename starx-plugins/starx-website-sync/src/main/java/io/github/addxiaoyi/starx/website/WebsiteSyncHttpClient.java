package io.github.addxiaoyi.starx.website;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WebsiteSyncHttpClient implements WebsiteSyncClient {
  private static final int MAX_RESPONSE_CHARS = 1_048_576;
  private final URI siteUrl;
  private final Duration requestTimeout;
  private final HttpClient http;
  private final Gson gson;

  public WebsiteSyncHttpClient(WebsiteSyncConfig config) {
    this(
        config.siteUrl(),
        config.heartbeat().requestTimeout(),
        HttpClient.newBuilder()
            .connectTimeout(config.heartbeat().connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        new Gson());
  }

  WebsiteSyncHttpClient(URI siteUrl, Duration requestTimeout, HttpClient http, Gson gson) {
    this.siteUrl = Objects.requireNonNull(siteUrl, "siteUrl");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    this.http = Objects.requireNonNull(http, "http");
    this.gson = Objects.requireNonNull(gson, "gson");
  }

  @Override
  public Enrollment enroll(
      SecretValue bootstrapToken,
      String nodeId,
      WebsitePlatform platform,
      List<String> capabilities
  ) throws WebsiteSyncApiException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("bootstrapToken", requireSecret(bootstrapToken, "bootstrap token"));
    body.put("nodeId", nodeId);
    body.put("platform", platform.wireName());
    body.put("capabilities", NodeCapabilities.normalize(capabilities));
    JsonObject response = post("/api/v1/plugin/enroll", null, body);
    JsonObject credential = requireObject(response, "credential");
    String token = requireString(credential, "token");
    String returnedNodeId = optionalString(credential, "nodeId", nodeId);
    List<String> scopes = stringList(credential.get("scopes"));
    return new Enrollment(SecretValue.of(token), returnedNodeId, scopes);
  }

  @Override
  public HeartbeatAck heartbeat(
      SecretValue nodeToken,
      String nodeId,
      List<String> capabilities,
      NodeSnapshot snapshot
  ) throws WebsiteSyncApiException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nodeId", nodeId);
    body.put("capabilities", NodeCapabilities.normalize(capabilities));
    body.put("snapshot", snapshotPayload(snapshot));
    JsonObject response = post(
        "/api/v1/plugin/heartbeat", requireSecret(nodeToken, "node token"), body);
    return new HeartbeatAck(
        optionalString(response, "nodeId", nodeId),
        requireString(response, "receivedAt"));
  }

  @Override
  public ManifestAck submitManifest(
      SecretValue nodeToken,
      Collection<PlayerTexture> entries
  ) throws WebsiteSyncApiException {
    return submitManifestPage(
        nodeToken, java.util.UUID.randomUUID().toString(), 0, 1, entries);
  }

  @Override
  public ManifestAck submitManifestPage(
      SecretValue nodeToken,
      String syncId,
      int page,
      int pages,
      Collection<PlayerTexture> entries
  ) throws WebsiteSyncApiException {
    if (syncId == null || !syncId.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,63}")) {
      throw new IllegalArgumentException("Texture manifest syncId is invalid");
    }
    if (pages < 1 || pages > 100 || page < 0 || page >= pages) {
      throw new IllegalArgumentException(
          "Texture manifest page metadata is outside protocol bounds");
    }
    List<PlayerTexture> stable = entries == null ? List.of() : entries.stream()
        .sorted(java.util.Comparator.comparing(PlayerTexture::playerUuid))
        .toList();
    if (stable.size() > 1_000) {
      throw new IllegalArgumentException(
          "A texture manifest page may contain at most 1000 entries");
    }
    JsonObject response = post(
        "/api/v1/plugin/skins/manifest",
        requireSecret(nodeToken, "node token"),
        Map.of(
            "syncId", syncId,
            "page", page,
            "pages", pages,
            "entries", stable));
    int accepted = response.has("accepted") ? response.get("accepted").getAsInt() : stable.size();
    List<MissingTexture> missing = new ArrayList<>();
    JsonElement hashes = response.get("missingHashes");
    if (hashes != null && hashes.isJsonArray()) {
      for (JsonElement element : hashes.getAsJsonArray()) {
        JsonObject value = element.getAsJsonObject();
        missing.add(new MissingTexture(
            requireString(value, "hash"),
            TextureKind.parse(requireString(value, "kind"))));
      }
    }
    return new ManifestAck(accepted, missing);
  }

  @Override
  public void uploadTexture(SecretValue nodeToken, TextureBlob texture)
      throws WebsiteSyncApiException {
    Objects.requireNonNull(texture, "texture");
    post(
        "/api/v1/plugin/textures/" + texture.sha256(),
        requireSecret(nodeToken, "node token"),
        Map.of(
            "kind", texture.kind().wireName(),
            "pngBase64", Base64.getEncoder().encodeToString(texture.pngBytes())));
  }

  private JsonObject post(String path, String bearerToken, Object body)
      throws WebsiteSyncApiException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path))
        .timeout(this.requestTimeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(
            this.gson.toJson(body), StandardCharsets.UTF_8));
    if (bearerToken != null) {
      builder.header("Authorization", "Bearer " + bearerToken);
    }
    HttpResponse<String> response;
    try {
      response = this.http.send(
          builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new WebsiteSyncApiException("Website sync request was interrupted", error);
    } catch (IOException | RuntimeException error) {
      throw new WebsiteSyncApiException("Website sync transport failed", error);
    }
    String responseBody = response.body() == null ? "" : response.body();
    if (responseBody.length() > MAX_RESPONSE_CHARS) {
      throw new WebsiteSyncApiException(
          response.statusCode(), "response_too_large", "response exceeded 1 MiB");
    }
    JsonObject json = parseObject(responseBody, response.statusCode());
    boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
    if (!success || !optionalBoolean(json, "ok", optionalBoolean(json, "success", success))) {
      String code = optionalString(json, "code", optionalString(json, "error", "http_error"));
      String message = optionalString(json, "message", "request failed");
      throw new WebsiteSyncApiException(response.statusCode(), code, message);
    }
    return json;
  }

  private URI endpoint(String path) {
    if (path == null || !path.startsWith("/api/v1/plugin/")) {
      throw new IllegalArgumentException("Unsupported website sync endpoint");
    }
    return this.siteUrl.resolve(path);
  }

  private static Map<String, Object> snapshotPayload(NodeSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    Map<String, Object> payload = new LinkedHashMap<>();
    put(payload, "pluginVersion", snapshot.pluginVersion());
    put(payload, "minecraftVersion", snapshot.minecraftVersion());
    put(payload, "onlinePlayers", snapshot.onlinePlayers());
    put(payload, "maxPlayers", snapshot.maxPlayers());
    put(payload, "tps", snapshot.tps());
    put(payload, "mspt", snapshot.mspt());
    payload.put("maintenance", snapshot.maintenance());
    List<Map<String, Object>> servers = new ArrayList<>();
    for (ServerSnapshot server : snapshot.servers()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("nodeId", server.nodeId());
      entry.put("name", server.name());
      put(entry, "platform", server.platform());
      put(entry, "minecraftVersion", server.minecraftVersion());
      entry.put("status", server.status().wireName());
      put(entry, "onlinePlayers", server.onlinePlayers());
      put(entry, "maxPlayers", server.maxPlayers());
      put(entry, "tps", server.tps());
      put(entry, "mspt", server.mspt());
      entry.put("maintenance", server.maintenance());
      entry.put("capabilities", server.capabilities());
      servers.add(entry);
    }
    payload.put("servers", servers);
    return payload;
  }

  private static void put(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private static String requireSecret(SecretValue value, String label) {
    if (value == null || !value.isPresent()) {
      throw new IllegalStateException(label + " is not configured");
    }
    return value.reveal();
  }

  private static JsonObject parseObject(String body, int statusCode)
      throws WebsiteSyncApiException {
    if (body == null || body.isBlank()) {
      if (statusCode >= 200 && statusCode < 300) {
        return new JsonObject();
      }
      throw new WebsiteSyncApiException(statusCode, "invalid_response", "empty response body");
    }
    try {
      JsonElement parsed = com.google.gson.JsonParser.parseString(body);
      if (!parsed.isJsonObject()) {
        throw new JsonParseException("response root is not an object");
      }
      return parsed.getAsJsonObject();
    } catch (JsonParseException error) {
      throw new WebsiteSyncApiException(statusCode, "invalid_response", "invalid JSON response");
    }
  }

  private static JsonObject requireObject(JsonObject object, String key)
      throws WebsiteSyncApiException {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonObject()) {
      throw new WebsiteSyncApiException(200, "invalid_response", "missing " + key);
    }
    return value.getAsJsonObject();
  }

  private static String requireString(JsonObject object, String key)
      throws WebsiteSyncApiException {
    String value = optionalString(object, key, "");
    if (value.isBlank()) {
      throw new WebsiteSyncApiException(200, "invalid_response", "missing " + key);
    }
    return value;
  }

  private static String optionalString(JsonObject object, String key, String fallback) {
    JsonElement value = object.get(key);
    return value == null || value.isJsonNull() ? fallback : value.getAsString();
  }

  private static boolean optionalBoolean(JsonObject object, String key, boolean fallback) {
    JsonElement value = object.get(key);
    return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
  }

  private static List<String> stringList(JsonElement value) {
    if (value == null || !value.isJsonArray()) {
      return List.of();
    }
    JsonArray array = value.getAsJsonArray();
    List<String> result = new ArrayList<>(array.size());
    for (JsonElement element : array) {
      if (element.isJsonPrimitive()) {
        result.add(element.getAsString());
      }
    }
    return List.copyOf(result);
  }
}
