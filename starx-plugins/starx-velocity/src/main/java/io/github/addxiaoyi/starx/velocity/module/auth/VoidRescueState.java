package io.github.addxiaoyi.starx.velocity.module.auth;

final class VoidRescueState {
  private final double threshold;
  private boolean teleportPending;

  VoidRescueState(double threshold) {
    if (!Double.isFinite(threshold)) {
      throw new IllegalArgumentException("Void rescue threshold must be finite");
    }
    this.threshold = threshold;
  }

  boolean shouldRescue(double y) {
    if (!Double.isFinite(y) || y >= threshold || this.teleportPending) {
      return false;
    }
    this.teleportPending = true;
    return true;
  }

  boolean observePosition(double y) {
    if (!this.teleportPending || !Double.isFinite(y) || y < this.threshold) {
      return false;
    }
    this.teleportPending = false;
    return true;
  }

  void cancelPending() {
    this.teleportPending = false;
  }
}