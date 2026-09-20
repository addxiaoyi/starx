package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public final class WebsiteOriginResolver {
  private WebsiteOriginResolver() {}

  public static String fromWebhook(StarxConfig.WebhookConfig webhook) {
    Objects.requireNonNull(webhook, "webhook");
    String rawUrl = webhook.url();
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new IllegalArgumentException("webhook.url is required for cross-device approval");
    }

    return fromUrl(rawUrl);
  }

  public static String fromUrl(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new IllegalArgumentException("website URL is required");
    }
    URI uri;
    try {
      uri = URI.create(rawUrl.trim());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("website URL must be a valid HTTP(S) URL", error);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
      throw new IllegalArgumentException("website URL must be a valid HTTP(S) URL");
    }
    int port = uri.getPort();
    return scheme + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
  }
}
