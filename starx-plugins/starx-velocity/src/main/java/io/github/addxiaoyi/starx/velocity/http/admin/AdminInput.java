package io.github.addxiaoyi.starx.velocity.http.admin;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class AdminInput {
  private AdminInput() {}

  static String requiredText(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(
          field + " too long (max " + maxLength + " characters)");
    }
    return normalized;
  }

  static String enumValue(String value, String field, Set<String> allowed) {
    String normalized = requiredText(value, field, 64).toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalized)) {
      String choices = allowed.stream().sorted().collect(Collectors.joining(", "));
      throw new IllegalArgumentException(
          "Invalid " + field + ". Must be one of: " + choices);
    }
    return normalized;
  }
}
