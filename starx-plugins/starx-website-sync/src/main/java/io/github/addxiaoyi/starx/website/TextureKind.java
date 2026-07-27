package io.github.addxiaoyi.starx.website;

import java.util.Locale;

public enum TextureKind {
  SKIN,
  CAPE;

  public String wireName() {
    return this.name().toLowerCase(Locale.ROOT);
  }

  public static TextureKind parse(String value) {
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("Texture kind must be skin or cape", error);
    }
  }
}
