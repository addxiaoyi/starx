package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

final class ReconnectTargetPolicy {
  private final Function<String, Optional<String>> selector;

  ReconnectTargetPolicy(Function<String, Optional<String>> selector) {
    this.selector = Objects.requireNonNull(selector, "selector");
  }

  Optional<String> resolve(String rememberedServer) {
    if (rememberedServer == null || rememberedServer.isBlank()) {
      return Optional.empty();
    }
    return Objects.requireNonNull(
        this.selector.apply(rememberedServer.trim()), "selector result");
  }
}
