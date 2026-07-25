package io.github.addxiaoyi.starx.server;

import java.util.Objects;
import java.util.UUID;

record BackendSkinProfile(
    UUID uuid,
    String name,
    String provider,
    String value,
    String signature
) {
  BackendSkinProfile {
    uuid = Objects.requireNonNull(uuid, "uuid");
    name = requireText(name, "name");
    provider = requireText(provider, "provider");
    value = requireText(value, "value");
    signature = signature == null ? "" : signature;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
