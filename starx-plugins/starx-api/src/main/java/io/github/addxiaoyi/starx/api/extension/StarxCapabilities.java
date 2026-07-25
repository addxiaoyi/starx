package io.github.addxiaoyi.starx.api.extension;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Stable capability identifiers used during extension compatibility checks. */
public final class StarxCapabilities {
  private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

  /** Extension registration service capability. */
  public static final String EXTENSIONS = "starx.service.extensions";
  /** Public event stream capability. */
  public static final String EVENTS = "starx.service.events";
  /** Velocity platform capability. */
  public static final String VELOCITY = "starx.platform.velocity";
  /** Paper platform capability. */
  public static final String PAPER = "starx.platform.paper";
  /** Folia platform capability. */
  public static final String FOLIA = "starx.platform.folia";
  /** Velocity authentication capability. */
  public static final String AUTH = "starx.velocity.auth";
  /** Velocity Uworld capability. */
  public static final String UWORLD = "starx.velocity.uworld";
  /** Velocity HTTP API capability. */
  public static final String HTTP_API = "starx.velocity.http-api";
  /** Velocity backend routing capability. */
  public static final String BACKEND_ROUTING = "starx.velocity.backend-routing";
  /** Backend bridge capability. */
  public static final String BACKEND_BRIDGE = "starx.backend.bridge";
  /** Backend status reporting capability. */
  public static final String BACKEND_STATUS = "starx.backend.status";
  /** Backend HTTP heartbeat capability. */
  public static final String BACKEND_HEARTBEAT = "starx.backend.heartbeat";
  /** Backend signed-skin lookup capability. */
  public static final String BACKEND_SKIN = "starx.backend.skin";
  /** PlaceholderAPI integration capability. */
  public static final String PLACEHOLDER_API = "starx.backend.placeholderapi";
  /** SkinsRestorer integration capability. */
  public static final String SKINS_RESTORER = "starx.backend.skinsrestorer";

  private StarxCapabilities() {}

  /**
   * Creates the immutable baseline capability set for a platform.
   *
   * @param platform runtime platform
   * @return core capability identifiers
   */
  public static Set<String> coreFor(PlatformKind platform) {
    Objects.requireNonNull(platform, "platform");
    LinkedHashSet<String> capabilities = new LinkedHashSet<>();
    capabilities.add(EXTENSIONS);
    capabilities.add(EVENTS);
    capabilities.add("starx.platform." + platform.name().toLowerCase(Locale.ROOT));
    return Set.copyOf(capabilities);
  }

  /**
   * Validates and normalizes a capability identifier.
   *
   * @param capability capability identifier
   * @return validated identifier
   * @throws IllegalArgumentException if the identifier format is invalid
   */
  public static String requireValid(String capability) {
    String value = Objects.requireNonNull(capability, "capability").trim();
    if (!IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid StarX capability identifier: " + capability);
    }
    return value;
  }
}
