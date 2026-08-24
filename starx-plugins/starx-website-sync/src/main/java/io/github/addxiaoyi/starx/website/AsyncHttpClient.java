package io.github.addxiaoyi.starx.website;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import java.util.function.Consumer;

/**
 * 异步 HTTP 客户端，防止阻塞线程并防止请求风暴。
 * 使用信号量进行速率限制，熔断器防止雪崩，指标收集器记录运行状况。
 */
public final class AsyncHttpClient implements WebsiteSyncClient {
  private static final int DEFAULT_MAX_CONCURRENT = 8;
  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private final URI siteUrl;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final int maxConcurrentRequests;
  private final HttpClient http;
  private final Semaphore concurrentRequests;
  private final Consumer<String> logger;
  private final com.google.gson.Gson gson;
  private final CircuitBreaker circuitBreaker;
  private final SyncMetrics metrics;

  public AsyncHttpClient(WebsiteSyncConfig config) {
    this(
        config,
        new SyncMetrics(),
        new CircuitBreaker(
            config.circuitBreaker().failureThreshold(),
            Duration.ofSeconds(config.circuitBreaker().openTimeoutSeconds())));
  }

  public AsyncHttpClient(WebsiteSyncConfig config, SyncMetrics metrics, CircuitBreaker circuitBreaker) {
    this(
        config.siteUrl(),
        config.heartbeat().connectTimeout(),
        config.heartbeat().requestTimeout(),
        config.enabled() ? DEFAULT_MAX_CONCURRENT : 1,
        HttpClient.newBuilder()
            .connectTimeout(config.heartbeat().connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        msg -> {
          if (config.enabled() && config.textures().enabled()) {
            // Only log when textures are enabled to avoid spam
            System.err.println(msg);
          }
        },
        new com.google.gson.Gson(),
        metrics,
        circuitBreaker);
  }

  // Additional constructor for full configuration (used by VelocityWebsiteSync)
  public AsyncHttpClient(
      URI siteUrl,
      Duration connectTimeout,
      Duration requestTimeout,
      int maxConcurrentRequests,
      HttpClient http,
      Consumer<String> logger,
      com.google.gson.Gson gson,
      SyncMetrics metrics,
      CircuitBreaker circuitBreaker
  ) {
    this.siteUrl = Objects.requireNonNull(siteUrl, "siteUrl");
    this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    this.maxConcurrentRequests = maxConcurrentRequests > 0 ? maxConcurrentRequests : DEFAULT_MAX_CONCURRENT;
    this.http = Objects.requireNonNull(http, "http");
    this.concurrentRequests = new Semaphore(this.maxConcurrentRequests);
    this.logger = logger == null ? ignored -> { } : logger;
    this.gson = Objects.requireNonNull(gson, "gson");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
  }

  @Override
  public Enrollment enroll(
      SecretValue bootstrapToken,
      String nodeId,
      WebsitePlatform platform,
      java.util.List<String> capabilities
  ) throws WebsiteSyncApiException {
    return sync(() -> {
      Map<String, Object> body = new java.util.LinkedHashMap<>();
      body.put("bootstrapToken", requireSecret(bootstrapToken, "bootstrap token"));
      body.put("nodeId", nodeId);
      body.put("platform", platform.wireName());
      body.put("capabilities", NodeCapabilities.normalize(capabilities));
      return post("/api/v1/plugin/enroll", null, body)
          .thenApply(response -> {
            if (response.statusCode >= 200 && response.statusCode < 300) {
              JsonObject credential = requireObjectUnchecked(response.json, "credential");
              String token = requireStringUnchecked(credential, "token");
              String returnedNodeId = optionalString(credential, "nodeId", nodeId);
              java.util.List<String> scopes = stringList(credential.get("scopes"));
              return new Enrollment(SecretValue.of(token), returnedNodeId, scopes);
            }
            throw apiError(response.statusCode, response.json,
                "code", "message", "enrollment failed");
          });
    });
  }

  @Override
  public HeartbeatAck heartbeat(
      SecretValue nodeToken,
      String nodeId,
      java.util.List<String> capabilities,
      NodeSnapshot snapshot
  ) throws WebsiteSyncApiException {
    return sync(() -> {
      Map<String, Object> body = new java.util.LinkedHashMap<>();
      body.put("nodeId", nodeId);
      body.put("capabilities", NodeCapabilities.normalize(capabilities));
      body.put("snapshot", snapshotPayload(snapshot));
      return post("/api/v1/plugin/heartbeat", requireSecret(nodeToken, "node token"), body)
          .thenApply(response -> {
            if (response.statusCode >= 200 && response.statusCode < 300) {
              return new HeartbeatAck(
                  optionalString(response.json, "nodeId", nodeId),
                  requireStringUnchecked(response.json, "receivedAt"));
            }
            throw apiError(response.statusCode, response.json,
                "code", "message", "heartbeat failed");
          });
    });
  }

  @Override
  public ManifestAck submitManifest(
      SecretValue nodeToken,
      java.util.Collection<PlayerTexture> entries
  ) throws WebsiteSyncApiException {
    return submitManifestPage(nodeToken, java.util.UUID.randomUUID().toString(), 0, 1, entries);
  }

  @Override
  public ManifestAck submitManifestPage(
      SecretValue nodeToken,
      String syncId,
      int page,
      int pages,
      java.util.Collection<PlayerTexture> entries
  ) throws WebsiteSyncApiException {
    if (syncId == null || !syncId.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,63}")) {
      throw new IllegalArgumentException("Texture manifest syncId is invalid");
    }
    if (pages < 1 || pages > 100 || page < 0 || page >= pages) {
      throw new IllegalArgumentException(
          "Texture manifest page metadata is outside protocol bounds");
    }
    java.util.List<PlayerTexture> stable = entries == null ? java.util.List.of() : entries.stream()
        .sorted(java.util.Comparator.comparing(PlayerTexture::playerUuid))
        .toList();
    if (stable.size() > 1_000) {
      throw new IllegalArgumentException(
          "A texture manifest page may contain at most 1000 entries");
    }

    return sync(() -> {
      Map<String, Object> body = new java.util.LinkedHashMap<>();
      body.put("syncId", syncId);
      body.put("page", page);
      body.put("pages", pages);
      body.put("entries", stable);
      return post("/api/v1/plugin/skins/manifest", requireSecret(nodeToken, "node token"), body)
          .thenApply(response -> {
            if (response.statusCode >= 200 && response.statusCode < 300) {
              int accepted = response.json.has("accepted")
                  ? response.json.get("accepted").getAsInt() : stable.size();
              java.util.List<MissingTexture> missing = new java.util.ArrayList<>();
              com.google.gson.JsonElement hashes = response.json.get("missingHashes");
              if (hashes != null && hashes.isJsonArray()) {
                for (com.google.gson.JsonElement element : hashes.getAsJsonArray()) {
                  JsonObject value = element.getAsJsonObject();
                  missing.add(new MissingTexture(
                      requireStringUnchecked(value, "hash"),
                      TextureKind.parse(requireStringUnchecked(value, "kind"))));
                }
              }
              return new ManifestAck(accepted, missing);
            }
            throw apiError(response.statusCode, response.json,
                "code", "message", "manifest submit failed");
          });
    });
  }

  @Override
  public void applyCatalogSkin(
      SecretValue nodeToken,
      String catalogId,
      UUID playerUuid,
      String username
  ) throws WebsiteSyncApiException {
    String id = Objects.requireNonNullElse(catalogId, "").trim();
    String playerName = Objects.requireNonNullElse(username, "").trim();
    if (id.isEmpty() || playerName.isEmpty()) {
      throw new IllegalArgumentException("Catalog ID and player name are required");
    }
    sync(() -> {
      Map<String, Object> body = Map.of(
          "catalogId", id,
          "playerUuid", Objects.requireNonNull(playerUuid, "playerUuid").toString(),
          "username", playerName);
      return post("/api/v1/plugin/skins/apply",
          requireSecret(nodeToken, "node token"), body)
          .thenApply(response -> null);
    });
  }

  @Override
  public void uploadTexture(SecretValue nodeToken, TextureBlob texture)
      throws WebsiteSyncApiException {
    Objects.requireNonNull(texture, "texture");
    sync(() -> {
      Map<String, Object> body = Map.of(
          "kind", texture.kind().wireName(),
          "pngBase64", Base64.getEncoder().encodeToString(texture.pngBytes()));
      return post("/api/v1/plugin/textures/" + texture.sha256(),
          requireSecret(nodeToken, "node token"), body)
          .thenApply(response -> null);
    });
  }

  /**
   * 同步等待异步操作完成，防止线程堆积。
   * 限制最大并发请求数量。
   */
  @SuppressWarnings("unchecked")
  private <T> T sync(java.util.function.Supplier<CompletableFuture<T>> asyncOperation)
      throws WebsiteSyncApiException {
    // 熔断检查：打开状态直接快速失败，避免线程堆积
    if (!this.circuitBreaker.allowRequest()) {
      this.metrics.recordRequestRejectedByCircuit();
      throw new WebsiteSyncApiException(503, "circuit_open",
          "Website sync circuit breaker is open; request rejected");
    }
    if (!concurrentRequests.tryAcquire()) {
      // 信号量被限制，触发熔断
      this.metrics.recordRequestRejectedByLimit();
      logger.accept("StarX website sync: concurrent request limit reached, rejecting request");
      throw new WebsiteSyncApiException(0, "concurrent_limit", "Too many concurrent requests");
    }
    long startMillis = System.currentTimeMillis();
    try {
      CompletableFuture<T> future = asyncOperation.get();
      try {
        T result = future.get(requestTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        this.circuitBreaker.recordSuccess();
        return result;
      } catch (java.util.concurrent.TimeoutException e) {
        this.circuitBreaker.recordFailure();
        logger.accept("StarX website sync: request timed out after " + requestTimeout.toMillis() + "ms");
        throw new WebsiteSyncApiException("Request timed out", e);
      } catch (java.util.concurrent.ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        this.circuitBreaker.recordFailure();
        if (cause instanceof WebsiteSyncApiException apiEx) {
          throw apiEx;
        }
        if (cause instanceof RuntimeException renEx) {
          throw renEx;
        }
        throw new WebsiteSyncApiException("Request failed", cause);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new WebsiteSyncApiException("Request interrupted", e);
      }
    } finally {
      concurrentRequests.release();
      long elapsed = System.currentTimeMillis() - startMillis;
      // 记录延迟供指标查询
      this.metrics.recordHeartbeatSuccess(elapsed);
    }
  }

  record HttpJsonResponse(int statusCode, JsonObject json, String rawBody) {
    static HttpJsonResponse of(int statusCode, String body) {
      JsonObject json;
      try {
        com.google.gson.JsonElement parsed = body == null || body.isBlank()
            ? new JsonObject()
            : com.google.gson.JsonParser.parseString(body);
        json = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
      } catch (com.google.gson.JsonParseException error) {
        json = new JsonObject();
      }
      return new HttpJsonResponse(statusCode, json, body);
    }
  }

  private CompletableFuture<HttpJsonResponse> post(
      String path, String bearerToken, Map<String, Object> body
  ) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path))
        .timeout(requestTimeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(
            this.gson.toJson(body), StandardCharsets.UTF_8));
    if (bearerToken != null) {
      builder.header("Authorization", "Bearer " + bearerToken);
    }

    HttpRequest request = builder.build();
    return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .thenApply(response -> HttpJsonResponse.of(
            response.statusCode(), response.body()))
        .orTimeout(requestTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .exceptionally(ex -> HttpJsonResponse.of(
            ex instanceof java.util.concurrent.TimeoutException ? 408 : 0, "{}"));
  }

  private URI endpoint(String path) {
    if (path == null || !path.startsWith("/api/v1/plugin/")) {
      throw new IllegalArgumentException("Unsupported website sync endpoint");
    }
    return this.siteUrl.resolve(path);
  }

  private static String requireSecret(SecretValue value, String label) {
    if (value == null || !value.isPresent()) {
      throw new IllegalStateException(label + " is not configured");
    }
    return value.reveal();
  }

  private static JsonObject requireObject(JsonObject object, String key)
      throws WebsiteSyncApiException {
    com.google.gson.JsonElement value = object.get(key);
    if (value == null || !value.isJsonObject()) {
      throw new WebsiteSyncApiException(200, "invalid_response", "missing " + key);
    }
    return value.getAsJsonObject();
  }

  /** Lambda 内使用的版本：把受检异常包装为 CompletionException 以通过编译。 */
  private static JsonObject requireObjectUnchecked(JsonObject object, String key) {
    try {
      return requireObject(object, key);
    } catch (WebsiteSyncApiException e) {
      throw new java.util.concurrent.CompletionException(e);
    }
  }

  private static String requireStringUnchecked(JsonObject object, String key) {
    String value = optionalString(object, key, "");
    if (value.isBlank()) {
      throw new java.util.concurrent.CompletionException(
          new WebsiteSyncApiException(200, "invalid_response", "missing " + key));
    }
    return value;
  }

  /** 构造 API 错误并包装为 CompletionException，供 CompletableFuture 链内抛出。 */
  private static java.util.concurrent.CompletionException apiError(
      int statusCode, JsonObject json, String codeKey, String messageKey, String fallbackMessage) {
    return new java.util.concurrent.CompletionException(new WebsiteSyncApiException(
        statusCode,
        optionalString(json, codeKey, "http_error"),
        optionalString(json, messageKey, fallbackMessage)));
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
    com.google.gson.JsonElement value = object.get(key);
    return value == null || value.isJsonNull() ? fallback : value.getAsString();
  }

  private static com.google.gson.JsonElement parseObject(String body, int statusCode)
      throws WebsiteSyncApiException {
    if (body == null || body.isBlank()) {
      if (statusCode >= 200 && statusCode < 300) {
        return new com.google.gson.JsonObject();
      }
      throw new WebsiteSyncApiException(statusCode, "invalid_response", "empty response body");
    }
    try {
      com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(body);
      if (!parsed.isJsonObject()) {
        throw new WebsiteSyncApiException(statusCode, "invalid_response", "response root is not an object");
      }
      return parsed.getAsJsonObject();
    } catch (com.google.gson.JsonParseException error) {
      throw new WebsiteSyncApiException(statusCode, "invalid_response", "invalid JSON response");
    }
  }

  private static java.util.List<String> stringList(com.google.gson.JsonElement value) {
    if (value == null || !value.isJsonArray()) {
      return java.util.List.of();
    }
    com.google.gson.JsonArray array = value.getAsJsonArray();
    java.util.List<String> result = new java.util.ArrayList<>(array.size());
    for (com.google.gson.JsonElement element : array) {
      if (element.isJsonPrimitive()) {
        result.add(element.getAsString());
      }
    }
    return java.util.List.copyOf(result);
  }

  private static Map<String, Object> snapshotPayload(NodeSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    put(payload, "pluginVersion", snapshot.pluginVersion());
    put(payload, "minecraftVersion", snapshot.minecraftVersion());
    put(payload, "onlinePlayers", snapshot.onlinePlayers());
    put(payload, "maxPlayers", snapshot.maxPlayers());
    put(payload, "tps", snapshot.tps());
    put(payload, "mspt", snapshot.mspt());
    payload.put("maintenance", snapshot.maintenance());
    java.util.List<Map<String, Object>> servers = new java.util.ArrayList<>();
    for (ServerSnapshot server : snapshot.servers()) {
      Map<String, Object> entry = new java.util.LinkedHashMap<>();
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

  /**
   * 获取当前熔断器状态。
   */
  public CircuitBreaker.State circuitState() {
    return this.circuitBreaker.getState();
  }

  /**
   * 获取监控指标快照。
   */
  public SyncMetrics.Snapshot metrics() {
    return this.metrics.snapshot();
  }
}