package io.github.addxiaoyi.starx.server;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class BackendSkinResolverRegistry implements BackendSkinResolver {
  private static final BackendSkinResolver EMPTY = (uuid, name) -> Optional.empty();

  private volatile BackendSkinResolver resolver = EMPTY;

  void replace(BackendSkinResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  void clear() {
    this.resolver = EMPTY;
  }

  @Override
  public Optional<BackendSkinProfile> find(UUID uuid, String name) {
    return this.resolver.find(uuid, name);
  }

  @Override
  public boolean store(UUID uuid, String name, String value, String signature) {
    return this.resolver.store(uuid, name, value, signature);
  }
}
