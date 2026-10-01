package io.github.addxiaoyi.starx.velocity.module.auth;

/**
 * Decides whether the one-time FastLogin unlock hint is worth showing.
 *
 * <p>FastLogin only offers premium login from a backend server, so the hint is useless while the
 * player is still held in the authentication world.
 */
final class PremiumHintPolicy {

  private PremiumHintPolicy() {
  }

  static boolean shouldHint(boolean fastLoginAvailable, boolean alreadyVerified, boolean alreadySent) {
    return fastLoginAvailable && !alreadyVerified && !alreadySent;
  }
}