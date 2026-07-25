package io.github.addxiaoyi.starx.server;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
interface BackendSkinResolver {
  Optional<BackendSkinProfile> find(UUID uuid, String name);
}
