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
}
