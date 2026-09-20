package io.github.addxiaoyi.starx.api.extension;

/** Lifecycle states reported for a registered extension. */
public enum StarxExtensionState {
  /** The extension is running its enable callback. */
  ENABLING,
  /** The extension enabled successfully. */
  ENABLED,
  /** The extension is running its disable callback. */
  DISABLING,
  /** The extension has been unregistered cleanly. */
  DISABLED,
  /** The extension lifecycle reported a failure. */
  FAILED
}
