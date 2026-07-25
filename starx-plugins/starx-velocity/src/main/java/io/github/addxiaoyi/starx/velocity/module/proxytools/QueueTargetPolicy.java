package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class QueueTargetPolicy {
  private final Selector selector;

  QueueTargetPolicy(Selector selector) {
    this.selector = Objects.requireNonNull(selector, "selector");
  }

  Optional<String> resolve(String preferredServer, Map<String, Integer> queueSizes) {
    if (preferredServer == null || preferredServer.isBlank()) {
      return Optional.empty();
    }
    Map<String, Integer> queues = queueSizes == null ? Map.of() : Map.copyOf(queueSizes);
    return Objects.requireNonNull(
        this.selector.select(preferredServer.trim(), queues), "selector result");
  }

  @FunctionalInterface
  interface Selector {
    Optional<String> select(String preferredServer, Map<String, Integer> queueSizes);
  }
}
