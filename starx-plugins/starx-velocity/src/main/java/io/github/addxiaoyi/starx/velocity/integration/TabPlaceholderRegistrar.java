package io.github.addxiaoyi.starx.velocity.integration;

import io.github.addxiaoyi.starx.velocity.variable.StarxVariableService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class TabPlaceholderRegistrar {

  private final Object manager;
  private final StarxVariableService variables;
  private final Function<UUID, Optional<StarxVariableService.PlayerContext>> contexts;
  private final int refreshMillis;
  private final Method register;
  private final Method unregister;
  private final List<String> registered = new ArrayList<>();

  public TabPlaceholderRegistrar(
      Object manager,
      StarxVariableService variables,
      Function<UUID, Optional<StarxVariableService.PlayerContext>> contexts,
      int refreshMillis) {
    this.manager = Objects.requireNonNull(manager, "manager");
    this.variables = Objects.requireNonNull(variables, "variables");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    if (refreshMillis < 100) {
      throw new IllegalArgumentException("TAB placeholder refresh must be at least 100ms");
    }
    this.refreshMillis = refreshMillis;
    this.register = findRegisterMethod(manager.getClass());
    this.unregister = findMethod(manager.getClass(), "unregisterPlaceholder", String.class);
  }

  public void registerAll() {
    if (!this.registered.isEmpty()) {
      throw new IllegalStateException("TAB placeholders are already registered");
    }
    try {
      for (String key : this.variables.keys()) {
        String identifier = "%" + key + "%";
        Function<Object, String> resolver = tabPlayer -> resolve(key, tabPlayer);
        this.register.invoke(this.manager, identifier, this.refreshMillis, resolver);
        this.registered.add(identifier);
      }
    } catch (IllegalAccessException | InvocationTargetException error) {
      unregisterAll();
      throw new IllegalStateException("Unable to register StarX placeholders with TAB", cause(error));
    }
  }

  public void unregisterAll() {
    RuntimeException failure = null;
    for (String identifier : List.copyOf(this.registered)) {
      try {
        this.unregister.invoke(this.manager, identifier);
        this.registered.remove(identifier);
      } catch (IllegalAccessException | InvocationTargetException error) {
        if (failure == null) {
          failure = new IllegalStateException("Unable to unregister StarX placeholders from TAB");
        }
        failure.addSuppressed(cause(error));
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private String resolve(String key, Object tabPlayer) {
    UUID playerId = playerId(tabPlayer);
    return this.contexts.apply(playerId)
        .map(context -> this.variables.resolve(key, context))
        .orElse("");
  }

  private static UUID playerId(Object tabPlayer) {
    Objects.requireNonNull(tabPlayer, "tabPlayer");
    try {
      Object value = tabPlayer.getClass().getMethod("getUniqueId").invoke(tabPlayer);
      if (value instanceof UUID uuid) {
        return uuid;
      }
      throw new IllegalStateException("TAB player getUniqueId() did not return UUID");
    } catch (NoSuchMethodException | IllegalAccessException error) {
      throw new IllegalStateException("Installed TAB player API is incompatible", error);
    } catch (InvocationTargetException error) {
      throw new IllegalStateException("TAB player UUID lookup failed", error.getCause());
    }
  }

  private static Method findRegisterMethod(Class<?> type) {
    for (Method method : type.getMethods()) {
      Class<?>[] parameters = method.getParameterTypes();
      if (method.getName().equals("registerPlayerPlaceholder")
          && parameters.length == 3
          && parameters[0] == String.class
          && (parameters[1] == int.class || parameters[1] == Integer.class)
          && Function.class.isAssignableFrom(parameters[2])) {
        return method;
      }
    }
    throw new IllegalArgumentException(
        "Installed TAB API does not expose registerPlayerPlaceholder(String, int, Function)");
  }

  private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
    try {
      return type.getMethod(name, parameters);
    } catch (NoSuchMethodException error) {
      throw new IllegalArgumentException("Installed TAB API does not expose " + name, error);
    }
  }

  private static Throwable cause(ReflectiveOperationException error) {
    return error instanceof InvocationTargetException invocation && invocation.getCause() != null
        ? invocation.getCause()
        : error;
  }
}
