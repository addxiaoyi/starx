package io.github.addxiaoyi.starx.velocity.module.skin;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class TextureUrlPolicy {
  private static final String MOJANG_TEXTURE_HOST = "textures.minecraft.net";

  private final Set<String> allowedHosts;

  private TextureUrlPolicy(Set<String> allowedHosts) {
    this.allowedHosts = Set.copyOf(allowedHosts);
  }

  static TextureUrlPolicy forWebsite(String profileBaseUrl) {
    URI profileUri = URI.create(Objects.requireNonNull(profileBaseUrl, "profileBaseUrl"));
    String host = normalizeHost(profileUri.getHost());
    if (host == null) {
      throw new IllegalArgumentException("Website profile URL must have a host");
    }
    return new TextureUrlPolicy(Set.of(MOJANG_TEXTURE_HOST, host));
  }

  static TextureUrlPolicy officialTexturesOnly() {
    return new TextureUrlPolicy(Set.of(MOJANG_TEXTURE_HOST));
  }

  boolean allows(String value) {
    if (value == null || value.isBlank() || value.length() > 2048) {
      return false;
    }
    try {
      URI uri = URI.create(value.trim());
      String host = normalizeHost(uri.getHost());
      return "https".equalsIgnoreCase(uri.getScheme())
          && host != null
          && allowedHosts.contains(host)
          && uri.getUserInfo() == null
          && uri.getFragment() == null
          && (uri.getPort() == -1 || uri.getPort() == 443);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private static String normalizeHost(String host) {
    if (host == null || host.isBlank()) {
      return null;
    }
    return host.toLowerCase(Locale.ROOT);
  }
}
