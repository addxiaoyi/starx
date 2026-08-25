package io.github.addxiaoyi.starx.api.extension;

/** Idempotent lifecycle handle returned after a successful extension registration. */
public interface StarxExtensionRegistration extends AutoCloseable {
  /**
   * Returns the documented service value.
   *
   * @return registered extension descriptor
   */
  StarxExtensionDescriptor descriptor();

  /**

   * Returns the documented service value.

   *

   * @return current immutable extension state

   */
  StarxExtensionSnapshot snapshot();

  /** Unregisters the extension; repeated calls have no effect. */
  @Override
  void close();

  /**
   * Returns the extension instance.
   *
   * @return extension instance
   */
  default StarxExtension extension() {
    return null;
  }

  /**
   * Returns the extension context.
   *
   * @return extension context
   */
  default StarxExtensionContext context() {
    return null;
  }

  /**
   * Returns the current health status.
   *
   * @return health status
   */
  default StarxExtensionHealth health() {
    return StarxExtensionHealth.unknown();
  }

  /**
   * Returns the current configuration.
   *
   * @return extension configuration
   */
  default StarxExtensionConfig config() {
    return StarxExtensionConfig.empty();
  }
}
