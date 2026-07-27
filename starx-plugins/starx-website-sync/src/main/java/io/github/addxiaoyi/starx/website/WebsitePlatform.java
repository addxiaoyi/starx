package io.github.addxiaoyi.starx.website;

import java.util.Locale;

public enum WebsitePlatform {
  VELOCITY,
  PAPER,
  FOLIA,
  BUNGEE,
  STANDALONE;

  public String wireName() {
    return this.name().toLowerCase(Locale.ROOT);
  }

  public static WebsitePlatform parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("website-sync.platform must not be blank");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "website-sync.platform must be velocity, paper, folia, bungee, or standalone",
          error);
    }
  }
}
