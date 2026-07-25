package io.github.addxiaoyi.starx.limbo.server;

final class KeepAliveResponseGuard {

  enum Decision {
    ACCEPT,
    IGNORE_STALE,
    REJECT
  }

  private boolean transitionGraceAvailable = true;

  Decision classify(boolean pending, long expectedId, long actualId) {
    if (pending && actualId == expectedId) {
      this.transitionGraceAvailable = false;
      return Decision.ACCEPT;
    }
    if (this.transitionGraceAvailable) {
      this.transitionGraceAvailable = false;
      return Decision.IGNORE_STALE;
    }
    return Decision.REJECT;
  }
}
