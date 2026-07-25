package io.github.addxiaoyi.starx.velocity.module.integrations.napcat;

import java.util.Objects;
import java.util.Optional;

final class NapCatFrameBuffer {
  private final int maxChars;
  private final StringBuilder text = new StringBuilder();
  private boolean discarding;

  NapCatFrameBuffer(int maxChars) {
    if (maxChars <= 0) throw new IllegalArgumentException("maxChars must be positive");
    this.maxChars = maxChars;
  }

  Optional<String> append(CharSequence fragment, boolean last) {
    Objects.requireNonNull(fragment, "fragment");
    if (this.discarding) {
      if (last) this.discarding = false;
      return Optional.empty();
    }
    if (fragment.length() > this.maxChars - this.text.length()) {
      this.text.setLength(0);
      this.discarding = !last;
      return Optional.empty();
    }
    this.text.append(fragment);
    if (!last) return Optional.empty();
    String message = this.text.toString();
    this.text.setLength(0);
    return Optional.of(message);
  }
}
