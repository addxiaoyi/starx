package io.github.addxiaoyi.starx.website;

import java.time.Duration;

final class ExponentialBackoff {
  private final long initialMillis;
  private final long maximumMillis;
  private long currentMillis;

  ExponentialBackoff(Duration initial, Duration maximum) {
    this.initialMillis = positive(initial, "initial");
    this.maximumMillis = positive(maximum, "maximum");
    if (this.initialMillis > this.maximumMillis) {
      throw new IllegalArgumentException("initial backoff exceeds maximum");
    }
    this.currentMillis = this.initialMillis;
  }

  synchronized Duration next() {
    long result = this.currentMillis;
    this.currentMillis = Math.min(this.maximumMillis, Math.multiplyExact(result, 2L));
    return Duration.ofMillis(result);
  }

  synchronized void reset() {
    this.currentMillis = this.initialMillis;
  }

  private static long positive(Duration duration, String label) {
    long millis = duration.toMillis();
    if (millis <= 0) {
      throw new IllegalArgumentException(label + " backoff must be positive");
    }
    return millis;
  }
}
