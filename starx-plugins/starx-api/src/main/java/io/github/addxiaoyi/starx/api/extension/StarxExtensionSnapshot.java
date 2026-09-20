package io.github.addxiaoyi.starx.api.extension;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-only extension state suitable for diagnostics.
 *
 * @param descriptor registered extension descriptor
 * @param state current lifecycle state
 * @param enabledAt enable completion timestamp, or {@code null}
 * @param failure failure text, or an empty string
 */
public record StarxExtensionSnapshot(
    StarxExtensionDescriptor descriptor,
    StarxExtensionState state,
    Instant enabledAt,
    String failure) {
  /**
   * Creates an immutable normalized snapshot.
   *
   * @param descriptor extension descriptor
   * @param state lifecycle state
   * @param enabledAt enable completion timestamp
   * @param failure failure text
   */
  public StarxExtensionSnapshot {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    state = Objects.requireNonNull(state, "state");
    failure = failure == null ? "" : failure;
  }
}
