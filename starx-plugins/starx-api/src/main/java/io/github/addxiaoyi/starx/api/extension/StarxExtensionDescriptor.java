package io.github.addxiaoyi.starx.api.extension;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable identity and compatibility declaration for a third-party extension.
 *
 * @param id stable lowercase namespaced identifier
 * @param name human-readable extension name
 * @param version extension implementation version
 * @param requiredApi minimum required StarX API version
 * @param requiredCapabilities capabilities required before enable
 */
public record StarxExtensionDescriptor(
    String id,
    String name,
    String version,
    ApiVersion requiredApi,
    Set<String> requiredCapabilities) {
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

  /**
   * Validates and creates an extension descriptor.
   *
   * @param id stable lowercase namespaced identifier
   * @param name human-readable extension name
   * @param version extension implementation version
   * @param requiredApi minimum required API version
   * @param requiredCapabilities required runtime capabilities
   */
  public StarxExtensionDescriptor {
    id = requireText(id, "id", 64);
    if (!ID.matcher(id).matches()) {
      throw new IllegalArgumentException("Extension id must be lowercase and namespaced: " + id);
    }
    name = requireText(name, "name", 96);
    version = requireText(version, "version", 64);
    requiredApi = Objects.requireNonNull(requiredApi, "requiredApi");
    LinkedHashSet<String> capabilities = new LinkedHashSet<>();
    if (requiredCapabilities != null) {
      requiredCapabilities.forEach(value -> capabilities.add(StarxCapabilities.requireValid(value)));
    }
    requiredCapabilities = Set.copyOf(capabilities);
  }

  /**
   * Creates a descriptor requiring the current API and no optional capabilities.
   *
   * @param id stable lowercase namespaced identifier
   * @param name human-readable extension name
   * @param version extension implementation version
   * @return validated descriptor
   */
  public static StarxExtensionDescriptor create(String id, String name, String version) {
    return new StarxExtensionDescriptor(id, name, version, StarxApi.VERSION, Set.of());
  }

  private static String requireText(String value, String label, int maxLength) {
    String normalized = Objects.requireNonNull(value, label).trim();
    if (normalized.isEmpty() || normalized.length() > maxLength) {
      throw new IllegalArgumentException(label + " must contain 1-" + maxLength + " characters");
    }
    return normalized;
  }
}
