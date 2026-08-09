package io.github.addxiaoyi.starx.server;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SkinsRestorerBackendSkinResolver implements BackendSkinResolver {
  private static final String PROVIDER_CLASS = "net.skinsrestorer.api.SkinsRestorerProvider";

  private final Object playerStorage;
  private final Object skinStorage;
  private final Logger logger;
  private final AtomicBoolean failureLogged = new AtomicBoolean();

  static SkinsRestorerBackendSkinResolver discover(Logger logger) {
    try {
      Class<?> provider = Class.forName(PROVIDER_CLASS);
      Object api = provider.getMethod("get").invoke(null);
      return new SkinsRestorerBackendSkinResolver(api, logger);
    } catch (ClassNotFoundException ignored) {
      return new SkinsRestorerBackendSkinResolver(null, null, logger);
    } catch (ReflectiveOperationException error) {
      logger.log(Level.WARNING, "SkinsRestorer API was found but could not be initialized", error);
      return new SkinsRestorerBackendSkinResolver(null, null, logger);
    }
  }

  SkinsRestorerBackendSkinResolver(Object api) {
    this(api, Logger.getLogger(SkinsRestorerBackendSkinResolver.class.getName()));
  }

  SkinsRestorerBackendSkinResolver(Object api, Logger logger) {
    this(read(api, "getPlayerStorage"), read(api, "getSkinStorage"), logger);
  }

  private SkinsRestorerBackendSkinResolver(Object playerStorage, Object skinStorage, Logger logger) {
    this.playerStorage = playerStorage;
    this.skinStorage = skinStorage;
    this.logger = logger;
  }

  boolean available() {
    return this.playerStorage != null;
  }

  String provider() {
    return this.available() ? "skinsrestorer" : "none";
  }

  @Override
  public Optional<BackendSkinProfile> find(UUID uuid, String name) {
    if (!this.available()) {
      return Optional.empty();
    }
    try {
      Optional<?> current = tryCurrentApi(uuid, name);
      if (current != null) {
        return current.map(data -> profile(uuid, name, data));
      }
      if (this.skinStorage == null) {
        return Optional.empty();
      }
      Optional<?> skinId = invokeOptional(this.playerStorage, "getSkinIdOfPlayer", uuid);
      if (skinId.isEmpty()) {
        return Optional.empty();
      }
      Optional<?> skinData = invokeOptional(
          this.skinStorage, "getSkinDataByIdentifier", skinId.get());
      return skinData.map(data -> profile(uuid, name, data));
    } catch (ReflectiveOperationException | ClassCastException | IllegalStateException error) {
      if (this.failureLogged.compareAndSet(false, true)) {
        this.logger.log(Level.WARNING,
            "SkinsRestorer profile lookup failed; skin bridge will use its fallback", error);
      }
      return Optional.empty();
    }
  }

  @Override
  public boolean store(UUID uuid, String name, String value, String signature) {
    if (!this.available() || this.skinStorage == null) {
      return false;
    }
    if (uuid == null || name == null || name.isBlank() || value == null || value.isBlank()) {
      throw new IllegalArgumentException("Skin update fields must not be blank");
    }
    try {
      Optional<?> existing = invokeOptional(this.playerStorage, "getSkinIdOfPlayer", uuid);
      String skinId = existing.map(SkinsRestorerBackendSkinResolver::identifierOfUnchecked)
          .orElseGet(() -> "starx-" + uuid.toString().replace("-", ""));
      writeSkinData(this.skinStorage, skinId, value, signature == null ? "" : signature);
      setSkinIdentifier(this.playerStorage, uuid, skinId);
      return true;
    } catch (ReflectiveOperationException | ClassCastException | IllegalStateException error) {
      if (this.failureLogged.compareAndSet(false, true)) {
        this.logger.log(Level.WARNING,
            "SkinsRestorer profile update failed; backend skin was not persisted", error);
      }
      return false;
    }
  }

  private Optional<?> tryCurrentApi(UUID uuid, String name) throws ReflectiveOperationException {
    try {
      return invokeOptional(this.playerStorage, "getSkinForPlayer", uuid, name);
    } catch (NoSuchMethodException ignored) {
      return null;
    }
  }

  private BackendSkinProfile profile(UUID uuid, String name, Object data) {
    try {
      String value = readString(data, "getValue", "value");
      String signature = readString(data, "getSignature", "signature");
      return new BackendSkinProfile(uuid, name, this.provider(), value, signature);
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String readString(Object target, String getter, String accessor)
      throws ReflectiveOperationException {
    Object value;
    try {
      value = invoke(target, getter);
    } catch (NoSuchMethodException ignored) {
      value = invoke(target, accessor);
    }
    if (value instanceof Optional<?> optional) {
      value = optional.isPresent() ? optional.get() : "";
    }
    return value == null ? "" : value.toString();
  }

  private static String identifierOfUnchecked(Object value) {
    try {
      if (value instanceof String identifier) {
        return identifier;
      }
      Object identifier = invoke(value, "getIdentifier");
      if (identifier instanceof String text && !text.isBlank()) {
        return text;
      }
      throw new IllegalStateException("SkinsRestorer returned an invalid skin identifier");
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("Failed to read SkinsRestorer skin identifier", error);
    }
  }

  private static void writeSkinData(Object storage, String skinId, String value, String signature)
      throws ReflectiveOperationException {
    try {
      invoke(storage, "setSkinData", skinId, value, signature);
      return;
    } catch (NoSuchMethodException ignored) {
      // SkinsRestorer 15.12 stores custom textures through SkinProperty.
    }
    for (Method method : storage.getClass().getMethods()) {
      Class<?>[] parameters = method.getParameterTypes();
      if (!method.getName().equals("setCustomSkinData")
          || parameters.length != 2
          || !parameters[0].isInstance(skinId)) {
        continue;
      }
      method.trySetAccessible();
      method.invoke(storage, skinId, skinPropertyFor(parameters[1], value, signature));
      return;
    }
    throw new NoSuchMethodException("setSkinData or setCustomSkinData in " + storage.getClass());
  }

  private static void setSkinIdentifier(Object storage, UUID uuid, String skinId)
      throws ReflectiveOperationException {
    for (Method method : storage.getClass().getMethods()) {
      Class<?>[] parameters = method.getParameterTypes();
      if (!method.getName().equals("setSkinIdOfPlayer")
          || parameters.length != 2
          || !parameters[0].isInstance(uuid)) {
        continue;
      }
      method.trySetAccessible();
      method.invoke(storage, uuid, skinIdentifierFor(parameters[1], skinId));
      return;
    }
    throw new NoSuchMethodException("setSkinIdOfPlayer in " + storage.getClass());
  }

  private static Object skinIdentifierFor(Class<?> targetType, String skinId)
      throws ReflectiveOperationException {
    if (targetType.isAssignableFrom(String.class)) {
      return skinId;
    }
    Method factory = targetType.getMethod("ofCustom", String.class);
    Object identifier = factory.invoke(null, skinId);
    if (targetType.isInstance(identifier)) {
      return identifier;
    }
    throw new IllegalStateException("SkinsRestorer returned an invalid skin identifier type");
  }

  private static Object skinPropertyFor(Class<?> targetType, String value, String signature)
      throws ReflectiveOperationException {
    Method factory = targetType.getMethod("of", String.class, String.class);
    Object property = factory.invoke(null, value, signature);
    if (targetType.isInstance(property)) {
      return property;
    }
    throw new IllegalStateException("SkinsRestorer returned an invalid skin property type");
  }

  private static Object read(Object target, String name) {
    if (target == null) {
      return null;
    }
    try {
      return invoke(target, name);
    } catch (ReflectiveOperationException error) {
      return null;
    }
  }

  private static Optional<?> invokeOptional(Object target, String name, Object... arguments)
      throws ReflectiveOperationException {
    Object value = invoke(target, name, arguments);
    return value instanceof Optional<?> optional ? optional : Optional.empty();
  }

  private static Object invoke(Object target, String name, Object... arguments)
      throws ReflectiveOperationException {
    Method method = findMethod(target.getClass(), name, arguments);
    method.trySetAccessible();
    return method.invoke(target, arguments);
  }

  private static Method findMethod(Class<?> type, String name, Object[] arguments)
      throws NoSuchMethodException {
    for (Method method : type.getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
        continue;
      }
      Class<?>[] parameters = method.getParameterTypes();
      boolean matches = true;
      for (int index = 0; index < parameters.length; index++) {
        if (arguments[index] != null && !parameters[index].isInstance(arguments[index])) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return method;
      }
    }
    throw new NoSuchMethodException(name + " in " + type.getName());
  }
}
