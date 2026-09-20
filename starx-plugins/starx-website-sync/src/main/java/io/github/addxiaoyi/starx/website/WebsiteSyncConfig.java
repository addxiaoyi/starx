package io.github.addxiaoyi.starx.website;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record WebsiteSyncConfig(
    boolean enabled,
    URI siteUrl,
    String nodeId,
    WebsitePlatform platform,
    SecretValue bootstrapToken,
    SecretValue nodeToken,
    Heartbeat heartbeat,
    Textures textures,
    CircuitBreakerConfig circuitBreaker
) {
  private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,63}");
  private static final Pattern SOURCE = Pattern.compile("[a-z0-9._-]{1,40}");

  public WebsiteSyncConfig {
    siteUrl = normalizeSiteUrl(siteUrl);
    nodeId = Objects.requireNonNullElse(nodeId, "").trim();
    if (!NODE_ID.matcher(nodeId).matches()) {
      throw new IllegalArgumentException(
          "website-sync.node-id must match " + NODE_ID.pattern());
    }
    platform = Objects.requireNonNull(platform, "platform");
    bootstrapToken = bootstrapToken == null ? SecretValue.empty() : bootstrapToken;
    nodeToken = nodeToken == null ? SecretValue.empty() : nodeToken;
    heartbeat = heartbeat == null ? Heartbeat.defaults() : heartbeat;
    textures = textures == null ? Textures.defaults() : textures;
    circuitBreaker = circuitBreaker == null ? CircuitBreakerConfig.defaults() : circuitBreaker;
  }

  /** 紧凑构造器：兼容旧调用点（无熔断配置）。 */
  public WebsiteSyncConfig(
      boolean enabled,
      URI siteUrl,
      String nodeId,
      WebsitePlatform platform,
      SecretValue bootstrapToken,
      SecretValue nodeToken,
      Heartbeat heartbeat,
      Textures textures
  ) {
    this(enabled, siteUrl, nodeId, platform, bootstrapToken, nodeToken,
        heartbeat, textures, null);
  }

  /**
   * 熔断器配置：连续失败达到阈值后打开熔断，冷却期后半开试探恢复。
   */
  public record CircuitBreakerConfig(
      boolean enabled,
      int failureThreshold,
      int openTimeoutSeconds
  ) {
    public CircuitBreakerConfig {
      if (failureThreshold < 1) {
        throw new IllegalArgumentException(
            "website-sync.circuit-breaker.failure-threshold must be at least 1");
      }
      if (openTimeoutSeconds < 5 || openTimeoutSeconds > 3600) {
        throw new IllegalArgumentException(
            "website-sync.circuit-breaker.open-timeout-seconds must be between 5 and 3600");
      }
    }

    public static CircuitBreakerConfig defaults() {
      return new CircuitBreakerConfig(true, 10, 60);
    }
  }

  public static WebsiteSyncConfig disabled(String nodeId, WebsitePlatform platform) {
    return new WebsiteSyncConfig(
        false,
        URI.create("https://star-web.top"),
        nodeId,
        platform,
        SecretValue.empty(),
        SecretValue.empty(),
        Heartbeat.defaults(),
        Textures.defaults());
  }

  /**
   * Returns a copy of this config with texture synchronization disabled.
   * Useful for environments where the texture CDN or Mojang API is unreachable.
   */
  public WebsiteSyncConfig withTexturesDisabled() {
    return new WebsiteSyncConfig(
        this.enabled,
        this.siteUrl,
        this.nodeId,
        this.platform,
        this.bootstrapToken,
        this.nodeToken,
        this.heartbeat,
        new Textures(false, this.textures.source(), this.textures.manifestIntervalSeconds(),
            this.textures.batchSize()));
  }

  public boolean needsEnrollment() {
    return this.enabled && !this.nodeToken.isPresent() && this.bootstrapToken.isPresent();
  }

  public boolean hasNodeCredential() {
    return this.enabled && this.nodeToken.isPresent();
  }

  private static URI normalizeSiteUrl(URI value) {
    URI uri = Objects.requireNonNull(value, "siteUrl").normalize();
    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          "website-sync.site-url must be an HTTP(S) URL without credentials, query, or fragment");
    }
    String text = uri.toString();
    while (text.endsWith("/")) {
      text = text.substring(0, text.length() - 1);
    }
    return URI.create(text);
  }

  public record Heartbeat(
      int intervalSeconds,
      int connectTimeoutMs,
      int requestTimeoutMs
  ) {
    public Heartbeat {
      if (intervalSeconds < 5 || intervalSeconds > 300) {
        throw new IllegalArgumentException(
            "website-sync.heartbeat.interval-seconds must be between 5 and 300");
      }
      if (connectTimeoutMs < 250 || connectTimeoutMs > 30_000) {
        throw new IllegalArgumentException(
            "website-sync.heartbeat.connect-timeout-ms must be between 250 and 30000");
      }
      if (requestTimeoutMs < connectTimeoutMs || requestTimeoutMs > 60_000) {
        throw new IllegalArgumentException(
            "website-sync.heartbeat.request-timeout-ms must be between connect timeout and 60000");
      }
    }

    public static Heartbeat defaults() {
      return new Heartbeat(15, 3_000, 8_000);
    }

    public Duration interval() {
      return Duration.ofSeconds(this.intervalSeconds);
    }

    public Duration connectTimeout() {
      return Duration.ofMillis(this.connectTimeoutMs);
    }

    public Duration requestTimeout() {
      return Duration.ofMillis(this.requestTimeoutMs);
    }
  }

  public record Textures(
      boolean enabled,
      String source,
      int manifestIntervalSeconds,
      int batchSize
  ) {
    public Textures {
      source = Objects.requireNonNullElse(source, "skinsrestorer").trim().toLowerCase();
      if (!SOURCE.matcher(source).matches()) {
        throw new IllegalArgumentException(
            "website-sync.textures.source must match " + SOURCE.pattern());
      }
      if (manifestIntervalSeconds < 60 || manifestIntervalSeconds > 86_400) {
        throw new IllegalArgumentException(
            "website-sync.textures.manifest-interval-seconds must be between 60 and 86400");
      }
      if (batchSize < 1 || batchSize > 1_000) {
        throw new IllegalArgumentException(
            "website-sync.textures.batch-size must be between 1 and 1000");
      }
    }

    public static Textures defaults() {
      return new Textures(true, "skinsrestorer", 300, 500);
    }

    public Duration manifestInterval() {
      return Duration.ofSeconds(this.manifestIntervalSeconds);
    }
  }
}
