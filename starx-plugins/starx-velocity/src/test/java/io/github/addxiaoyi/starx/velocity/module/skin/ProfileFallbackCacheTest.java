package io.github.addxiaoyi.starx.velocity.module.skin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class ProfileFallbackCacheTest {
  @Test
  void servesARecentVerifiedProfileAfterTheWebsiteFails() {
    WebsiteSkinProfile profile = WebsiteSkinProfile.parse(
        "{\"id\":\"abc\",\"name\":\"Alex\",\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/abc\"}}}",
        new Gson(), TextureUrlPolicy.officialTexturesOnly()).orElseThrow();
    Instant now = Instant.parse("2026-07-22T00:00:00Z");
    ProfileFallbackCache cache = new ProfileFallbackCache(Duration.ofHours(24));
    cache.put("Alex", profile, now);

    assertTrue(cache.get("alex", now.plus(Duration.ofHours(23))).isPresent());
    assertTrue(cache.get("alex", now.plus(Duration.ofHours(25))).isEmpty());
  }

  @Test
  void profileCacheIsBounded() {
    ProfileFallbackCache cache = new ProfileFallbackCache(Duration.ofHours(24), 1);
    Instant now = Instant.parse("2026-07-22T00:00:00Z");
    cache.put("Alex", profile("a", "Alex"), now);
    cache.put("Steve", profile("b", "Steve"), now.plusSeconds(1));

    assertTrue(cache.get("Alex", now.plusSeconds(2)).isEmpty());
    assertTrue(cache.get("Steve", now.plusSeconds(2)).isPresent());
  }

  private static WebsiteSkinProfile profile(String id, String name) {
    return WebsiteSkinProfile.parse(
        "{\"id\":\"" + id + "\",\"name\":\"" + name
            + "\",\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/"
            + id + "\"}}}", new Gson(), TextureUrlPolicy.officialTexturesOnly()).orElseThrow();
  }
}
