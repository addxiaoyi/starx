package io.github.addxiaoyi.starx.api.extension;

/** Implemented by StarX platform entrypoints for dependency-safe service discovery. */
public interface StarxServiceProvider {
  /**
   * Returns the documented service value.
   *
   * @return initialized StarX public service
   */
  StarxService starxService();
}
