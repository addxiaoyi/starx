package io.github.addxiaoyi.starx.velocity.module.uworld;

record UworldCountdownFrame(float progress, int level, boolean playSound, float pitch) {

  static UworldCountdownFrame at(long totalSeconds, long remainingSeconds) {
    long total = Math.max(1, totalSeconds);
    int remaining = (int) Math.max(0, Math.min(Integer.MAX_VALUE, remainingSeconds));
    float progress = Math.max(0f, Math.min(1f, (float) remaining / (float) total));
    boolean urgent = remaining > 0 && remaining <= 10;
    boolean playSound = remaining > 0 && (urgent || remaining % 5 == 0);
    float pitch = urgent ? 1f + (10 - remaining) * 0.05f : 0.8f;
    return new UworldCountdownFrame(progress, remaining, playSound, pitch);
  }
}
