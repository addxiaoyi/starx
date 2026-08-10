package io.github.addxiaoyi.starx.server;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    } catch (LinkageError error) {
      logger.log(Level.WARNING,
          "SkinsRestorer API is incompatible with this server; skin bridge will use its fallback",
          error);
      return new SkinsRestorerBackendSkinResolver(null, null, logger);
    }
  }

  SkinsRestorerBackendSkinResolver(Object api) {
    this(api, Logger.getLogger(SkinsRestorerBackendSkinResolver.class.getName()));
  }

  SkinsRestorerBackendSkinResolver(Object api, Logger logger) {
    this(read(api, "getPlayerStorage", logger), read(api, "getSkinStorage", null), logger);
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
      if (current != null && current.isPresent()) {
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
    } catch (ReflectiveOperationException | ClassCastException | IllegalStateException | LinkageError error) {
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
      String skinId = skinIdForWrite(existing, uuid);
      writeSkinData(this.skinStorage, skinId, value, signature == null ? "" : signature);
      setSkinIdentifier(this.playerStorage, uuid, skinId);
      return true;
    } catch (ReflectiveOperationException | ClassCastException | IllegalStateException | LinkageError error) {
      if (this.failureLogged.compareAndSet(false, true)) {
        this.logger.log(Level.WARNING,
            "SkinsRestorer profile update failed; backend skin was not persisted", error);
      }
      return false;
    }
  }

  private Optional<?> tryCurrentApi(UUID uuid, String name) throws ReflectiveOperationException {
    Optional<?> byUuid = null;
    try {
      // The name overload may call Mojang for UUIDs that are not Mojang profiles.
      byUuid = invokeOptional(this.playerStorage, "getSkinOfPlayer", uuid);
      if (byUuid.isPresent()) {
        return byUuid;
      }
    } catch (NoSuchMethodException ignored) {
      // Fall through to the name lookup exposed by older storage implementations.
    }
    try {
      return invokeOptional(this.playerStorage, "getSkinForPlayer", uuid, name);
    } catch (NoSuchMethodException ignored) {
      try {
        return invokeOptional(this.playerStorage, "getSkinForPlayer", uuid, name, false);
      } catch (NoSuchMethodException ignoredAgain) {
        return byUuid;
      }
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

  private static String skinIdForWrite(Optional<?> existing, UUID uuid)
      throws ReflectiveOperationException {
    if (existing.isPresent() && isCustomIdentifier(existing.get())) {
      return identifierOfUnchecked(existing.get());
    }
    return "starx-" + uuid.toString().replace("-", "");
  }

  private static boolean isCustomIdentifier(Object value) throws ReflectiveOperationException {
    if (value instanceof String) {
      return true;
    }
    try {
      Object type = invoke(value, "getSkinType");
      if (type == null) {
        return false;
      }
      String name = type instanceof Enum<?> enumValue ? enumValue.name() : type.toString();
      return "CUSTOM".equalsIgnoreCase(name);
    } catch (NoSuchMethodException ignored) {
      return true;
    }
  }

  private static void writeSkinData(Object storage, String skinId, String value, String signature)
      throws ReflectiveOperationException {
    SkinDataWriter writer = findSkinDataWriter(storage, skinId, value, signature);
    if (writer != null) {
      if (!writer.method().trySetAccessible()) {
        throw new IllegalAccessException("Cannot access " + writer.method());
      }
      writer.method().invoke(storage, writer.arguments());
      return;
    }
    throw new NoSuchMethodException("setSkinData or setCustomSkinData in " + storage.getClass());
  }

  private static SkinDataWriter findSkinDataWriter(
      Object storage, String skinId, String value, String signature) {
    SkinDataWriter selected = null;
    for (Method method : storage.getClass().getMethods()) {
      Class<?>[] parameters = method.getParameterTypes();
      if (method.getName().equals("setSkinData") && parameters.length == 3) {
        Object identifier;
        try {
          identifier = skinIdentifierFor(parameters[0], skinId);
        } catch (ReflectiveOperationException | IllegalStateException ignored) {
          continue;
        }
        int score = matchScore(parameters[0], identifier)
            + matchScore(parameters[1], value)
            + matchScore(parameters[2], signature);
        if (score < 0) {
          continue;
        }
        selected = selectSkinDataWriter(
            selected, method, new Object[] {identifier, value, signature}, score);
      }
    }
    if (selected != null) {
      return selected;
    }
    for (Method method : storage.getClass().getMethods()) {
      Class<?>[] parameters = method.getParameterTypes();
      if (!method.getName().equals("setCustomSkinData") || parameters.length != 2) {
        continue;
      }
      Object identifier;
      Object property;
      try {
        identifier = skinIdentifierFor(parameters[0], skinId);
        property = skinPropertyFor(parameters[1], value, signature);
      } catch (ReflectiveOperationException | IllegalStateException ignored) {
        continue;
      }
      int score = matchScore(parameters[0], identifier) + matchScore(parameters[1], property);
      if (score < 0) {
        continue;
      }
      selected = selectSkinDataWriter(
          selected, method, new Object[] {identifier, property}, score);
    }
    return selected;
  }

  private static SkinDataWriter selectSkinDataWriter(
      SkinDataWriter current, Method method, Object[] arguments, int score) {
    String key = methodKey(method);
    if (current == null || score > current.score()
        || score == current.score() && key.compareTo(current.key()) < 0) {
      return new SkinDataWriter(method, arguments, score, key);
    }
    return current;
  }

  private static void setSkinIdentifier(Object storage, UUID uuid, String skinId)
      throws ReflectiveOperationException {
    Method selected = null;
    Object selectedIdentifier = null;
    int selectedScore = -1;
    String selectedKey = null;
    for (Method method : storage.getClass().getMethods()) {
      Class<?>[] parameters = method.getParameterTypes();
      if (!method.getName().equals("setSkinIdOfPlayer")
          || parameters.length != 2
          || matchScore(parameters[0], uuid) < 0) {
        continue;
      }
      Object identifier;
      try {
        identifier = skinIdentifierFor(parameters[1], skinId);
      } catch (ReflectiveOperationException | IllegalStateException ignored) {
        continue;
      }
      int score = matchScore(parameters[0], uuid) + matchScore(parameters[1], identifier);
      if (score < 0) {
        continue;
      }
      String key = methodKey(method);
      if (selected == null || score > selectedScore || score == selectedScore && key.compareTo(selectedKey) < 0) {
        selected = method;
        selectedIdentifier = identifier;
        selectedScore = score;
        selectedKey = key;
      }
    }
    if (selected != null) {
      if (!selected.trySetAccessible()) {
        throw new IllegalAccessException("Cannot access " + selected);
      }
      selected.invoke(storage, uuid, selectedIdentifier);
      return;
    }
    throw new NoSuchMethodException("setSkinIdOfPlayer in " + storage.getClass());
  }

  private static Object skinIdentifierFor(Class<?> targetType, String skinId)
      throws ReflectiveOperationException {
    if (matchScore(targetType, skinId) >= 0) {
      return skinId;
    }
    for (String factoryName : new String[] {"ofCustom", "of"}) {
      try {
        Method factory = targetType.getMethod(factoryName, String.class);
        if (!Modifier.isStatic(factory.getModifiers()) || !factory.trySetAccessible()) {
          continue;
        }
        Object identifier = factory.invoke(null, skinId);
        if (targetType.isInstance(identifier)) {
          return identifier;
        }
      } catch (ReflectiveOperationException ignored) {
        // Older API variants may expose only a constructor.
      }
    }
    Constructor<?> constructor = targetType.getDeclaredConstructor(String.class);
    if (!constructor.trySetAccessible()) {
      throw new IllegalAccessException("Cannot access " + constructor);
    }
    Object identifier = constructor.newInstance(skinId);
    if (targetType.isInstance(identifier)) {
      return identifier;
    }
    throw new IllegalStateException("SkinsRestorer returned an invalid skin identifier type");
  }

  private static Object skinPropertyFor(Class<?> targetType, String value, String signature)
      throws ReflectiveOperationException {
    try {
      Method factory = targetType.getMethod("of", String.class, String.class);
      if (!Modifier.isStatic(factory.getModifiers()) || !factory.trySetAccessible()) {
        throw new NoSuchMethodException("Static SkinProperty factory is unavailable");
      }
      Object property = factory.invoke(null, value, signature);
      if (targetType.isInstance(property)) {
        return property;
      }
    } catch (ReflectiveOperationException ignored) {
      // Older API variants may expose only a constructor.
    }
    Constructor<?> constructor = targetType.getDeclaredConstructor(String.class, String.class);
    if (!constructor.trySetAccessible()) {
      throw new IllegalAccessException("Cannot access " + constructor);
    }
    Object property = constructor.newInstance(value, signature);
    if (targetType.isInstance(property)) {
      return property;
    }
    throw new IllegalStateException("SkinsRestorer returned an invalid skin property type");
  }

  private static Object read(Object target, String name, Logger logger) {
    if (target == null) {
      return null;
    }
    try {
      return invoke(target, name);
    } catch (ReflectiveOperationException | LinkageError error) {
      if (logger != null) {
        logger.log(Level.WARNING,
            "SkinsRestorer " + name + " could not be initialized; skin bridge will use its fallback",
            error);
      }
      return null;
    }
  }

  private static Optional<?> invokeOptional(Object target, String name, Object... arguments)
      throws ReflectiveOperationException {
    Object value = invoke(target, name, arguments);
    if (value == null) {
      return Optional.empty();
    }
    return value instanceof Optional<?> optional ? optional : Optional.of(value);
  }

  private static Object invoke(Object target, String name, Object... arguments)
      throws ReflectiveOperationException {
    Method method = findMethod(target.getClass(), name, arguments);
    if (!method.trySetAccessible()) {
      throw new IllegalAccessException("Cannot access " + method);
    }
    return method.invoke(target, arguments);
  }

  private static Method findMethod(Class<?> type, String name, Object[] arguments)
      throws NoSuchMethodException {
    Method selected = null;
    int selectedScore = -1;
    String selectedKey = null;
    for (Method method : type.getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
        continue;
      }
      Class<?>[] parameters = method.getParameterTypes();
      int score = 0;
      for (int index = 0; index < parameters.length; index++) {
        int match = matchScore(parameters[index], arguments[index]);
        if (match < 0) {
          score = -1;
          break;
        }
        score += match;
      }
      if (score < 0) {
        continue;
      }
      String key = methodKey(method);
      if (selected == null || score > selectedScore || score == selectedScore && key.compareTo(selectedKey) < 0) {
        selected = method;
        selectedScore = score;
        selectedKey = key;
      }
    }
    if (selected != null) {
      return selected;
    }
    throw new NoSuchMethodException(name + " in " + type.getName());
  }

  private static int matchScore(Class<?> parameter, Object argument) {
    if (argument == null) {
      return parameter.isPrimitive() ? -1 : typeSpecificity(parameter);
    }
    Class<?> wrapped = wrap(parameter);
    Class<?> actual = argument.getClass();
    if (!wrapped.isAssignableFrom(actual)) {
      return -1;
    }
    int distance = typeDistance(actual, wrapped);
    return distance == 0 ? 1000 : 100 - distance;
  }

  private static int typeDistance(Class<?> actual, Class<?> expected) {
    if (actual.equals(expected)) {
      return 0;
    }
    int distance = 32;
    Class<?> parent = actual.getSuperclass();
    if (parent != null && expected.isAssignableFrom(parent)) {
      distance = Math.min(distance, 1 + typeDistance(parent, expected));
    }
    for (Class<?> interfaceType : actual.getInterfaces()) {
      if (expected.isAssignableFrom(interfaceType)) {
        distance = Math.min(distance, 1 + typeDistance(interfaceType, expected));
      }
    }
    return distance;
  }

  private static int typeSpecificity(Class<?> type) {
    Class<?> wrapped = wrap(type);
    int specificity = 1;
    Class<?> parent = wrapped.getSuperclass();
    if (parent != null) {
      specificity = Math.max(specificity, 1 + typeSpecificity(parent));
    }
    for (Class<?> interfaceType : wrapped.getInterfaces()) {
      specificity = Math.max(specificity, 1 + typeSpecificity(interfaceType));
    }
    return specificity;
  }

  private static String methodKey(Method method) {
    StringBuilder key = new StringBuilder(method.getName()).append('(');
    for (Class<?> parameter : method.getParameterTypes()) {
      key.append(parameter.getName()).append(';');
    }
    return key.append(')').toString();
  }

  private static Class<?> wrap(Class<?> type) {
    if (type == Boolean.TYPE) return Boolean.class;
    if (type == Byte.TYPE) return Byte.class;
    if (type == Short.TYPE) return Short.class;
    if (type == Integer.TYPE) return Integer.class;
    if (type == Long.TYPE) return Long.class;
    if (type == Float.TYPE) return Float.class;
    if (type == Double.TYPE) return Double.class;
    if (type == Character.TYPE) return Character.class;
    return type;
  }

  private record SkinDataWriter(Method method, Object[] arguments, int score, String key) {
  }
}
