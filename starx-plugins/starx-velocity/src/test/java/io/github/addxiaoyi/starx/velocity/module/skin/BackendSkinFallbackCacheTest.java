package io.github.addxiaoyi.starx.velocity.module.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BackendSkinFallbackCacheTest {
  @TempDir Path directory;

  @Test
  void survivesProcessRecreationAndRejectsExpiredEntries() {
    UUID uuid = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-23T00:00:00Z");
    BackendSkinData skin = new BackendSkinData(uuid, "Alex", "Paper", "texture-value", "sig");
    BackendSkinFallbackCache first = cache(Duration.ofHours(2));
    first.put(skin, now);

    BackendSkinFallbackCache recreated = cache(Duration.ofHours(2));
    assertEquals(skin, recreated.find(uuid, "alex", now.plusSeconds(60)).orElseThrow());
    assertTrue(recreated.find(uuid, "Alex", now.plus(Duration.ofHours(3))).isEmpty());
  }

  @Test
  void rejectsAndDeletesTamperedCacheFiles() throws Exception {
    UUID uuid = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-23T00:00:00Z");
    BackendSkinFallbackCache cache = cache(Duration.ofHours(2));
    cache.put(new BackendSkinData(uuid, "Alex", "Paper", "original", "sig"), now);
    Path file = directory.resolve(uuid + ".properties");
    String tampered = Files.readString(file).replace("original", "attacker-value");
    Files.writeString(file, tampered);

    assertFalse(cache.find(uuid, "Alex", now.plusSeconds(1)).isPresent());
    assertFalse(Files.exists(file));
  }

  private BackendSkinFallbackCache cache(Duration ttl) {
    return new BackendSkinFallbackCache(directory, ttl, 10, Logger.getAnonymousLogger());
  }
}
