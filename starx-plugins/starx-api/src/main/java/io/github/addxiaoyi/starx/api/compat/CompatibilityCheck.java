package io.github.addxiaoyi.starx.api.compat;

import java.util.Objects;

/** One compatibility decision without any secret or machine credential. */
public record CompatibilityCheck(
    String id,
    String component,
    String detectedVersion,
    String supportedRange,
    CompatibilityStatus status,
    String message
) {
  public CompatibilityCheck {
    id = requireText(id, "id");
    component = requireText(component, "component");
    detectedVersion = normalize(detectedVersion);
    supportedRange = normalize(supportedRange);
    status = Objects.requireNonNull(status, "status");
    message = normalize(message);
  }

  public boolean blocksStrictStartup() {
    return this.status == CompatibilityStatus.UNSUPPORTED;
  }

  private static String requireText(String value, String name) {
    String normalized = normalize(value);
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder normalized = new StringBuilder(Math.min(value.length(), 512));
    boolean pendingSpace = false;
    for (int index = 0; index < value.length() && normalized.length() < 512; index++) {
      char character = value.charAt(index);
      if (Character.isWhitespace(character) || Character.isISOControl(character)) {
        pendingSpace = normalized.length() > 0;
      } else {
        if (pendingSpace && normalized.length() < 512) {
          normalized.append(' ');
        }
        pendingSpace = false;
        if (normalized.length() < 512) {
          normalized.append(character);
        }
      }
    }
    return normalized.toString().strip();
  }
}
