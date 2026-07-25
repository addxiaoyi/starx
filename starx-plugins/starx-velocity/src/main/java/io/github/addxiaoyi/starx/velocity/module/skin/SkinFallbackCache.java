package io.github.addxiaoyi.starx.velocity.module.skin;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class SkinFallbackCache {
  private static final int DEFAULT_MAX_ENTRIES = 4096;
  private final Duration ttl;
  private final int maxEntries;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  SkinFallbackCache(Duration ttl) {
    this(ttl, DEFAULT_MAX_ENTRIES);
  }

  SkinFallbackCache(Duration ttl, int maxEntries) {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Skin fallback TTL must be positive");
    }
    if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");
    this.ttl = ttl;
    this.maxEntries = maxEntries;
  }

  void put(String name, SkinDto skin, Instant now) {
    if (name == null || name.isBlank() || skin == null || now == null) return;
    String identity = identity(skin);
    if (identity == null) return;
    this.prune(now);
    this.entries.put(normalize(name), new Entry(skin, checksum(identity), now.plus(this.ttl)));
    this.trim();
  }

  private void prune(Instant now) {
    this.entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
  }

  private void trim() {
    while (this.entries.size() > this.maxEntries) {
      this.entries.entrySet().stream()
          .min(Map.Entry.comparingByValue((left, right) -> left.expiresAt().compareTo(right.expiresAt())))
          .ifPresent(entry -> this.entries.remove(entry.getKey(), entry.getValue()));
    }
  }

  Optional<SkinDto> get(String name, Instant now) {
    if (name == null || name.isBlank() || now == null) return Optional.empty();
    Entry entry = this.entries.get(normalize(name));
    if (entry == null) return Optional.empty();
    if (!entry.expiresAt().isAfter(now)) {
      this.entries.remove(normalize(name), entry);
      return Optional.empty();
    }
    String identity = identity(entry.skin());
    return identity != null && entry.checksum().equals(checksum(identity))
        ? Optional.of(entry.skin()) : Optional.empty();
  }

  private static String normalize(String name) {
    return name.trim().toLowerCase(Locale.ROOT);
  }

  private static String identity(SkinDto skin) {
    String textureUrl = skin.textureUrl();
    String skinId = skin.skinId();
    if ((textureUrl == null || textureUrl.isBlank()) && (skinId == null || skinId.isBlank())) {
      return null;
    }
    return String.valueOf(skinId) + "|" + String.valueOf(textureUrl);
  }

  private static String checksum(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private record Entry(SkinDto skin, String checksum, Instant expiresAt) { }
}
