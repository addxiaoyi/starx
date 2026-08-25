package io.github.addxiaoyi.starx.api.extension;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
 * @param dependencies required extension dependencies
 */
public record StarxExtensionDescriptor(
    String id,
    String name,
    String version,
    ApiVersion requiredApi,
    Set<String> requiredCapabilities,
    List<ExtensionDependency> dependencies) {
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

  /**
   * Validates and creates an extension descriptor.
   *
   * @param id stable lowercase namespaced identifier
   * @param name human-readable extension name
   * @param version extension implementation version
   * @param requiredApi minimum required StarX API version
   * @param requiredCapabilities required runtime capabilities
   * @param dependencies required extension dependencies
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
    dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
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
    return new StarxExtensionDescriptor(id, name, version, StarxApi.VERSION, Set.of(), List.of());
  }

  /**
   * Creates a descriptor with dependencies.
   *
   * @param id stable lowercase namespaced identifier
   * @param name human-readable extension name
   * @param version extension implementation version
   * @param dependencies extension dependencies
   * @return validated descriptor
   */
  public static StarxExtensionDescriptor create(String id, String name, String version,
                                                  List<ExtensionDependency> dependencies) {
    return new StarxExtensionDescriptor(id, name, version, StarxApi.VERSION, Set.of(), dependencies);
  }

  /**
   * Creates a full descriptor with all options.
   *
   * @param id stable lowercase namespaced identifier
   * @param name human-readable extension name
   * @param version extension implementation version
   * @param requiredApi minimum required StarX API version
   * @param requiredCapabilities required runtime capabilities
   * @param dependencies extension dependencies
   * @return validated descriptor
   */
  public static StarxExtensionDescriptor of(String id, String name, String version,
                                             ApiVersion requiredApi, Set<String> requiredCapabilities,
                                             List<ExtensionDependency> dependencies) {
    return new StarxExtensionDescriptor(id, name, version,
        requiredApi != null ? requiredApi : StarxApi.VERSION,
        requiredCapabilities, dependencies);
  }

  private static String requireText(String value, String label, int maxLength) {
    String normalized = Objects.requireNonNull(value, label).trim();
    if (normalized.isEmpty() || normalized.length() > maxLength) {
      throw new IllegalArgumentException(label + " must contain 1-" + maxLength + " characters");
    }
    return normalized;
  }

  /**
   * Extension dependency declaration.
   *
   * @param extensionId id of the required extension
   * @param minVersion minimum compatible version (null = any version)
   * @param maxVersion maximum compatible version (null = any version)
   */
  public record ExtensionDependency(String extensionId, String minVersion, String maxVersion) {
    public ExtensionDependency {
      extensionId = Objects.requireNonNull(extensionId, "extensionId").trim();
      if (extensionId.isEmpty()) {
        throw new IllegalArgumentException("extensionId must not be blank");
      }
    }

    /**
     * Creates a dependency on any version of the extension.
     *
     * @param extensionId id of the required extension
     * @return extension dependency
     */
    public static ExtensionDependency anyVersion(String extensionId) {
      return new ExtensionDependency(extensionId, null, null);
    }

    /**
     * Creates a dependency with a minimum version.
     *
     * @param extensionId id of the required extension
     * @param minVersion minimum compatible version
     * @return extension dependency
     */
    public static ExtensionDependency minVersion(String extensionId, String minVersion) {
      return new ExtensionDependency(extensionId, minVersion, null);
    }

    /**
     * Creates a dependency with version range.
     *
     * @param extensionId id of the required extension
     * @param minVersion minimum compatible version
     * @param maxVersion maximum compatible version
     * @return extension dependency
     */
    public static ExtensionDependency versionRange(String extensionId, String minVersion, String maxVersion) {
      return new ExtensionDependency(extensionId, minVersion, maxVersion);
    }

    /**
     * Checks if a given version satisfies this dependency.
     *
     * @param version the version to check
     * @return true if the version satisfies the dependency
     */
    public boolean isVersionSatisfied(String version) {
      if (version == null || version.isBlank()) {
        return minVersion == null;
      }

      // Simple version comparison for major.minor.patch format
      int vMajor = parseMajor(version);
      int vMinor = parseMinor(version);
      int vPatch = parsePatch(version);

      if (minVersion != null) {
        int mMajor = parseMajor(minVersion);
        int mMinor = parseMinor(minVersion);
        int mPatch = parsePatch(minVersion);
        if (vMajor < mMajor ||
            (vMajor == mMajor && vMinor < mMinor) ||
            (vMajor == mMajor && vMinor == mMinor && vPatch < mPatch)) {
          return false;
        }
      }

      if (maxVersion != null) {
        int xMajor = parseMajor(maxVersion);
        int xMinor = parseMinor(maxVersion);
        int xPatch = parsePatch(maxVersion);
        if (vMajor > xMajor ||
            (vMajor == xMajor && vMinor > xMinor) ||
            (vMajor == xMajor && vMinor == xMinor && vPatch > xPatch)) {
          return false;
        }
      }

      return true;
    }

    private static int parseMajor(String version) {
      String[] parts = version.split("\\.");
      try {
        return Integer.parseInt(parts[0]);
      } catch (NumberFormatException e) {
        return 0;
      }
    }

    private static int parseMinor(String version) {
      String[] parts = version.split("\\.");
      try {
        return parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
      } catch (NumberFormatException e) {
        return 0;
      }
    }

    private static int parsePatch(String version) {
      String[] parts = version.split("\\.");
      try {
        return parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
      } catch (NumberFormatException e) {
        return 0;
      }
    }
  }
}
