package io.github.addxiaoyi.starx.velocity.module.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SkinFallbackCacheTest {
  @Test
  void keepsOnlyVerifiedRecentProfilesAndExpiresThem() {
    Instant now = Instant.parse("2026-07-22T00:00:00Z");
    SkinFallbackCache cache = new SkinFallbackCache(Duration.ofHours(24));
    SkinDto skin = new SkinDto(UUID.randomUUID(), "Alex", "skin-1", null, null,
        "https://textures.minecraft.net/texture/abc");

    cache.put("Alex", skin, now);

    assertEquals(skin, cache.get("alex", now.plus(Duration.ofHours(23))).orElseThrow());
    assertTrue(cache.get("alex", now.plus(Duration.ofHours(25))).isEmpty());
  }

  @Test
  void rejectsProfilesWithoutAStableTextureIdentity() {
    SkinFallbackCache cache = new SkinFallbackCache(Duration.ofHours(24));
    SkinDto invalid = new SkinDto(UUID.randomUUID(), "Alex", null, null, null, null);

    cache.put("Alex", invalid, Instant.now());

    assertTrue(cache.get("Alex", Instant.now()).isEmpty());
  }

  @Test
  void evictsOldestEntryWhenCapacityIsReached() {
    SkinFallbackCache cache = new SkinFallbackCache(Duration.ofHours(24), 2);
    Instant now = Instant.parse("2026-07-22T00:00:00Z");
    cache.put("first", skin("first"), now);
    cache.put("second", skin("second"), now.plusSeconds(1));
    cache.put("third", skin("third"), now.plusSeconds(2));

    assertTrue(cache.get("first", now.plusSeconds(3)).isEmpty());
    assertTrue(cache.get("second", now.plusSeconds(3)).isPresent());
    assertTrue(cache.get("third", now.plusSeconds(3)).isPresent());
  }

  private static SkinDto skin(String id) {
    return new SkinDto(UUID.randomUUID(), id, id, null, null,
        "https://textures.minecraft.net/texture/" + id);
  }
}
