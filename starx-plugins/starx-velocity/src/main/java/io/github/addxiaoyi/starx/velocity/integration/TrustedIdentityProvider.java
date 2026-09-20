package io.github.addxiaoyi.starx.velocity.integration;

import java.util.UUID;

@FunctionalInterface
public interface TrustedIdentityProvider {

  boolean isTrusted(UUID playerId);

  default ClientPlatform platform(UUID playerId) {
    return isTrusted(playerId) ? ClientPlatform.BEDROCK : ClientPlatform.JAVA;
  }

  static TrustedIdentityProvider none() {
    return playerId -> false;
  }

  enum ClientPlatform {
    JAVA("Java版"),
    BEDROCK("基岩版");

    private final String label;

    ClientPlatform(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }
  }
}
