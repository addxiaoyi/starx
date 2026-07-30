package io.github.addxiaoyi.starx.api.compat;

/** Ordered compatibility severity used by runtime diagnostics and release gates. */
public enum CompatibilityStatus {
  /** The component is inside the certified compatibility range. */
  SUPPORTED(0),
  /** The component could not be identified well enough for a decision. */
  UNKNOWN(1),
  /** The component can run with safe degradation but is not fully certified. */
  DEGRADED(2),
  /** The component is incompatible with a required StarX baseline. */
  UNSUPPORTED(3);

  private final int severity;

  CompatibilityStatus(int severity) {
    this.severity = severity;
  }

  /**
   * Returns the ordering value used to aggregate compatibility results.
   *
   * @return non-negative severity, where a larger value is more severe
   */
  public int severity() {
    return this.severity;
  }

  /**
   * Selects the more severe of two compatibility statuses.
   *
   * @param left first status
   * @param right second status
   * @return the status with the greater severity
   */
  public static CompatibilityStatus worst(
      CompatibilityStatus left,
      CompatibilityStatus right
  ) {
    return left.severity >= right.severity ? left : right;
  }
}
