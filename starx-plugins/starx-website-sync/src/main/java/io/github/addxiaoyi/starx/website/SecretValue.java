package io.github.addxiaoyi.starx.website;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/** A secret value whose string representation is always redacted. */
public final class SecretValue {
  private static final String REDACTED = "[REDACTED]";
  private final char[] value;

  private SecretValue(char[] value) {
    this.value = value;
  }

  public static SecretValue of(String value) {
    String normalized = Objects.requireNonNullElse(value, "").trim();
    return new SecretValue(normalized.toCharArray());
  }

  public static SecretValue empty() {
    return new SecretValue(new char[0]);
  }

  public boolean isPresent() {
    return this.value.length > 0;
  }

  public String reveal() {
    return new String(this.value);
  }

  @Override
  public String toString() {
    return REDACTED;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SecretValue secret)) {
      return false;
    }
    byte[] left = this.reveal().getBytes(StandardCharsets.UTF_8);
    byte[] right = secret.reveal().getBytes(StandardCharsets.UTF_8);
    try {
      return MessageDigest.isEqual(left, right);
    } finally {
      Arrays.fill(left, (byte) 0);
      Arrays.fill(right, (byte) 0);
    }
  }

  @Override
  public int hashCode() {
    return 31 * this.value.length;
  }
}
