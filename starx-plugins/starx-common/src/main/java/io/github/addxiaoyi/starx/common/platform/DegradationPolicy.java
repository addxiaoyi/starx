package io.github.addxiaoyi.starx.common.platform;

import java.util.Objects;

public final class DegradationPolicy {
  public Fallback choose(Service service, boolean available, boolean cached) {
    Objects.requireNonNull(service, "service");
    if (available) return Fallback.PRIMARY;
    return switch (service) {
      case WEBSITE -> Fallback.LOCAL_PASSWORD_AND_TOTP;
      case SKIN -> cached ? Fallback.VERIFIED_SKIN_CACHE : Fallback.BUNDLED_DEFAULT_SKIN;
      case TARGET_SERVER -> Fallback.COMPATIBLE_HEALTHY_SERVER;
    };
  }

  public enum Service {
    WEBSITE,
    SKIN,
    TARGET_SERVER
  }

  public enum Fallback {
    PRIMARY,
    LOCAL_PASSWORD_AND_TOTP,
    VERIFIED_SKIN_CACHE,
    BUNDLED_DEFAULT_SKIN,
    COMPATIBLE_HEALTHY_SERVER
  }
}
