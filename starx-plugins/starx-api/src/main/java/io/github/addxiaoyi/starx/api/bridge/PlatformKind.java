package io.github.addxiaoyi.starx.api.bridge;

/** Platform identity encoded in StarX bridge and extension contracts. */
public enum PlatformKind {
  /** Velocity proxy runtime. */
  VELOCITY(1),
  /** Paper backend runtime. */
  PAPER(2),
  /** Folia regionized backend runtime. */
  FOLIA(3);

  private final int wireId;

  PlatformKind(int wireId) {
    this.wireId = wireId;
  }

  /**
   * Returns the stable numeric wire identifier.
   *
   * @return protocol wire identifier
   */
  public int wireId() {
    return this.wireId;
  }

  /**
   * Resolves a platform from its stable wire identifier.
   *
   * @param wireId protocol wire identifier
   * @return matching platform
   * @throws IllegalArgumentException if the identifier is unknown
   */
  public static PlatformKind fromWireId(int wireId) {
    for (PlatformKind kind : values()) {
      if (kind.wireId == wireId) {
        return kind;
      }
    }
    throw new IllegalArgumentException("Unknown StarX platform id: " + wireId);
  }
}
