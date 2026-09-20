package io.github.addxiaoyi.starx.website;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record MissingTexture(String hash, TextureKind kind) {
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public MissingTexture {
    hash = Objects.requireNonNullElse(hash, "").trim().toLowerCase(Locale.ROOT);
    if (!HASH.matcher(hash).matches()) {
      throw new IllegalArgumentException("Missing texture hash must be a lowercase SHA-256 value");
    }
    kind = Objects.requireNonNull(kind, "kind");
  }
}
