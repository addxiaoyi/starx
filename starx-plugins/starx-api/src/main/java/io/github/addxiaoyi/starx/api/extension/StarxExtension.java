package io.github.addxiaoyi.starx.api.extension;

/** Lifecycle implemented by a third-party StarX extension. */
public interface StarxExtension {
  /**
   * Enables the extension after compatibility checks pass.
   *
   * @param context extension-owned runtime context
   * @throws Exception when initialization cannot complete
   */
  void onEnable(StarxExtensionContext context) throws Exception;

  /**
   * Releases extension-owned resources during unregister or StarX shutdown.
   *
   * @param context extension-owned runtime context
   * @throws Exception when cleanup reports a failure
   */
  default void onDisable(StarxExtensionContext context) throws Exception {}
}
