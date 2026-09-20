package io.github.addxiaoyi.starx.api.extension;

/** Idempotent event-listener handle. */
@FunctionalInterface
public interface StarxEventSubscription extends AutoCloseable {
  /** Cancels the subscription; repeated calls have no effect. */
  @Override
  void close();
}
