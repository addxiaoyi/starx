package io.github.addxiaoyi.starx.server;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
interface BackendSkinResolver {
  Optional<BackendSkinProfile> find(UUID uuid, String name);

  default boolean store(UUID uuid, String name, String value, String signature) {
    return false;
  }
}
