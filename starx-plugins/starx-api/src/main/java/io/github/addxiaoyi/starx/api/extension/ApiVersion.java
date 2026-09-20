package io.github.addxiaoyi.starx.api.extension;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic version of the public StarX extension API.
 *
 * @param major compatibility-breaking version component
 * @param minor backward-compatible feature version component
 * @param patch backward-compatible fix version component
 */
public record ApiVersion(int major, int minor, int patch) implements Comparable<ApiVersion> {
  private static final Pattern VERSION = Pattern.compile("([1-9][0-9]*)\\.([0-9]+)\\.([0-9]+)");

  /**
   * Validates and creates an API version.
   *
   * @param major major component, at least one
   * @param minor non-negative minor component
   * @param patch non-negative patch component
   */
  public ApiVersion {
    if (major < 1 || minor < 0 || patch < 0) {
      throw new IllegalArgumentException("API version must be major>=1, minor>=0, patch>=0");
    }
  }

  /**
   * Parses a strict {@code major.minor.patch} version.
   *
   * @param value version text
   * @return parsed API version
   * @throws IllegalArgumentException if the value is not a valid API version
   */
  public static ApiVersion parse(String value) {
    Matcher matcher = VERSION.matcher(Objects.requireNonNull(value, "value").trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("API version must use major.minor.patch: " + value);
    }
    return new ApiVersion(
        Integer.parseInt(matcher.group(1)),
        Integer.parseInt(matcher.group(2)),
        Integer.parseInt(matcher.group(3)));
  }

  /**
   * Checks whether this runtime version satisfies a required version in the same major line.
   *
   * @param required minimum required API version
   * @return {@code true} when this version is compatible and not older
   */
  public boolean supports(ApiVersion required) {
    Objects.requireNonNull(required, "required");
    return this.major == required.major && this.compareTo(required) >= 0;
  }

  /** {@inheritDoc} */
  @Override
  public int compareTo(ApiVersion other) {
    Objects.requireNonNull(other, "other");
    int result = Integer.compare(this.major, other.major);
    if (result != 0) return result;
    result = Integer.compare(this.minor, other.minor);
    return result != 0 ? result : Integer.compare(this.patch, other.patch);
  }

  /**
   * Returns strict semantic-version text.
   *
   * @return {@code major.minor.patch}
   */
  @Override
  public String toString() {
    return this.major + "." + this.minor + "." + this.patch;
  }
}
