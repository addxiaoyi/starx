package io.github.addxiaoyi.starx.velocity.module.skin;

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

final class ProfileFallbackCache {
  private static final int DEFAULT_MAX_ENTRIES = 4096;
  private final Duration ttl;
  private final int maxEntries;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  ProfileFallbackCache(Duration ttl) {
    this(ttl, DEFAULT_MAX_ENTRIES);
  }

  ProfileFallbackCache(Duration ttl, int maxEntries) {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Profile fallback TTL must be positive");
    }
    if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");
    this.ttl = ttl;
    this.maxEntries = maxEntries;
  }

  void put(String name, WebsiteSkinProfile profile, Instant now) {
    if (name == null || name.isBlank() || profile == null || now == null) return;
    String identity = identity(profile);
    if (identity == null) return;
    prune(now);
    entries.put(normalize(name), new Entry(profile, checksum(identity), now.plus(ttl)));
    trim();
  }

  private void prune(Instant now) {
    entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
  }

  private void trim() {
    while (entries.size() > maxEntries) {
      entries.entrySet().stream()
          .min(Map.Entry.comparingByValue((left, right) -> left.expiresAt().compareTo(right.expiresAt())))
          .ifPresent(entry -> entries.remove(entry.getKey(), entry.getValue()));
    }
  }

  Optional<WebsiteSkinProfile> get(String name, Instant now) {
    if (name == null || name.isBlank() || now == null) return Optional.empty();
    String key = normalize(name);
    Entry entry = entries.get(key);
    if (entry == null) return Optional.empty();
    if (!entry.expiresAt().isAfter(now)) {
      entries.remove(key, entry);
      return Optional.empty();
    }
    String identity = identity(entry.profile());
    return identity != null && entry.checksum().equals(checksum(identity))
        ? Optional.of(entry.profile()) : Optional.empty();
  }

  private static String normalize(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static String identity(WebsiteSkinProfile profile) {
    String texture = profile.textureUrl();
    if ((texture == null || texture.isBlank()) && (profile.id() == null || profile.id().isBlank())) {
      return null;
    }
    return String.valueOf(profile.id()) + "|" + String.valueOf(texture);
  }

  private static String checksum(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private record Entry(WebsiteSkinProfile profile, String checksum, Instant expiresAt) { }
}
