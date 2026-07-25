package io.github.addxiaoyi.starx.api.extension;

/** Public compatibility constants for third-party StarX extensions. */
public final class StarxApi {
  /** Current public extension API version. */
  public static final ApiVersion VERSION = new ApiVersion(1, 0, 0);
  /** Oldest API version accepted by this runtime line. */
  public static final ApiVersion MINIMUM_SUPPORTED = new ApiVersion(1, 0, 0);
  /** Wildcard event subscription selector. */
  public static final String ALL_EVENTS = "*";

  private StarxApi() {}

  /**
   * Checks whether the current public API can satisfy a required version.
   *
   * @param required minimum version declared by an extension
   * @return {@code true} when the requirement is supported
   */
  public static boolean supports(ApiVersion required) {
    return VERSION.supports(required) && required.compareTo(MINIMUM_SUPPORTED) >= 0;
  }
}
