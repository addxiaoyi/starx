package io.github.addxiaoyi.starx.website;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record PlayerTextureRecord(PlayerTexture manifest, Map<TextureKind, TextureBlob> blobs) {
  public PlayerTextureRecord {
    manifest = Objects.requireNonNull(manifest, "manifest");
    EnumMap<TextureKind, TextureBlob> normalized = new EnumMap<>(TextureKind.class);
    if (blobs != null) {
      normalized.putAll(blobs);
    }
    verifyHash(manifest.skinHash(), normalized.get(TextureKind.SKIN), "skin");
    verifyHash(manifest.capeHash(), normalized.get(TextureKind.CAPE), "cape");
    blobs = Map.copyOf(normalized);
  }

  public Optional<TextureBlob> blob(TextureKind kind) {
    return Optional.ofNullable(this.blobs.get(kind));
  }

  private static void verifyHash(String expected, TextureBlob blob, String label) {
    if (blob != null && !Objects.equals(expected, blob.sha256())) {
      throw new IllegalArgumentException(label + " blob hash does not match manifest");
    }
  }
}
