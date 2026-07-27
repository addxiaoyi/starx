package io.github.addxiaoyi.starx.api.compat;

/** Ordered compatibility severity used by runtime diagnostics and release gates. */
public enum CompatibilityStatus {
  SUPPORTED(0),
  UNKNOWN(1),
  DEGRADED(2),
  UNSUPPORTED(3);

  private final int severity;

  CompatibilityStatus(int severity) {
    this.severity = severity;
  }

  public int severity() {
    return this.severity;
  }

  public static CompatibilityStatus worst(
      CompatibilityStatus left,
      CompatibilityStatus right
  ) {
    return left.severity >= right.severity ? left : right;
  }
}
