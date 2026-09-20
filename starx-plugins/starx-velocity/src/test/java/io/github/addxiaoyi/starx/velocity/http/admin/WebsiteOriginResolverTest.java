package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import org.junit.jupiter.api.Test;

class WebsiteOriginResolverTest {
  @Test
  void extractsOnlyTheOriginFromTheConfiguredWebhook() {
    StarxConfig.WebhookConfig webhook = new StarxConfig.WebhookConfig(
        "https://star-web.top/api/v1/plugin/callback", "secret");

    assertEquals("https://star-web.top", WebsiteOriginResolver.fromWebhook(webhook));
  }

  @Test
  void normalizesConfiguredWebsiteUrlToBrowserOrigin() {
    assertEquals("https://star-web.top", WebsiteOriginResolver.fromUrl(
        "HTTPS://star-web.top/bind/start?source=game"));
    assertEquals("http://127.0.0.1:5181", WebsiteOriginResolver.fromUrl(
        "http://127.0.0.1:5181/account"));
  }

  @Test
  void rejectsMissingOrNonHttpWebsiteEndpoints() {
    assertThrows(IllegalArgumentException.class, () -> WebsiteOriginResolver.fromWebhook(
        new StarxConfig.WebhookConfig("", "secret")));
    assertThrows(IllegalArgumentException.class, () -> WebsiteOriginResolver.fromWebhook(
        new StarxConfig.WebhookConfig("file:///tmp/callback", "secret")));
  }
}
