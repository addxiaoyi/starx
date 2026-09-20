package io.github.addxiaoyi.starx.website;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

public record TextureBlob(TextureKind kind, byte[] pngBytes, String sha256) {
  public static final int MAX_BYTES = 512 * 1024;

  public TextureBlob {
    kind = Objects.requireNonNull(kind, "kind");
    pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
    if (pngBytes.length == 0 || pngBytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Texture PNG must be between 1 byte and 512 KiB");
    }
    String actual = HexFormat.of().formatHex(sha256(pngBytes));
    if (sha256 == null || !MessageDigest.isEqual(
        actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        sha256.toLowerCase(java.util.Locale.ROOT)
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
      throw new IllegalArgumentException("Texture PNG hash does not match its bytes");
    }
    sha256 = actual;
    validatePng(kind, pngBytes);
  }

  @Override
  public byte[] pngBytes() {
    return this.pngBytes.clone();
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static void validatePng(TextureKind kind, byte[] bytes) {
    byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    if (bytes.length < 24) {
      throw new IllegalArgumentException("Texture is not a complete PNG");
    }
    for (int index = 0; index < signature.length; index++) {
      if (bytes[index] != signature[index]) {
        throw new IllegalArgumentException("Texture is not a PNG");
      }
    }
    int width = readInt(bytes, 16);
    int height = readInt(bytes, 20);
    boolean valid = kind == TextureKind.SKIN
        ? width == 64 && (height == 64 || height == 32)
        : width == 64 && (height == 32 || height == 17);
    if (!valid) {
      throw new IllegalArgumentException(
          "Invalid " + kind.wireName() + " dimensions: " + width + "x" + height);
    }
  }

  private static int readInt(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff) << 24
        | (bytes[offset + 1] & 0xff) << 16
        | (bytes[offset + 2] & 0xff) << 8
        | bytes[offset + 3] & 0xff;
  }
}
