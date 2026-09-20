package io.github.addxiaoyi.starx.velocity.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;

public final class FloodgateIdentityProvider implements TrustedIdentityProvider {

  private final Object api;
  private final Method isFloodgatePlayer;

  private FloodgateIdentityProvider(Object api, Method isFloodgatePlayer) {
    this.api = api;
    this.isFloodgatePlayer = isFloodgatePlayer;
  }

  public static FloodgateIdentityProvider fromApi(Object api) {
    Objects.requireNonNull(api, "api");
    try {
      Method method = api.getClass().getMethod("isFloodgatePlayer", UUID.class);
      if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) {
        throw new IllegalArgumentException(
            "FloodgateApi.isFloodgatePlayer(UUID) must return boolean");
      }
      return new FloodgateIdentityProvider(api, method);
    } catch (NoSuchMethodException error) {
      throw new IllegalArgumentException(
          "Installed Floodgate API does not expose isFloodgatePlayer(UUID)", error);
    }
  }

  @Override
  public boolean isTrusted(UUID playerId) {
    Objects.requireNonNull(playerId, "playerId");
    try {
      return (Boolean) this.isFloodgatePlayer.invoke(this.api, playerId);
    } catch (IllegalAccessException | InvocationTargetException error) {
      throw new IllegalStateException("Floodgate identity check failed for " + playerId, error);
    }
  }
}
