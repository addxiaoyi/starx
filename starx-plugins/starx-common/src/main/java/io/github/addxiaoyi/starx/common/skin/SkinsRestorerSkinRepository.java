/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.skin;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SkinsRestorerSkinRepository
implements SkinRepository {
    private static final Logger LOGGER = Logger.getLogger(SkinsRestorerSkinRepository.class.getName());
    private static final String PROVIDER_CLASS = "net.skinsrestorer.api.SkinsRestorerProvider";
    private final boolean available;
    private final Object playerStorage;
    private final Object skinStorage;

    public SkinsRestorerSkinRepository() {
        Object api = null;
        Object playerStorageTmp = null;
        Object skinStorageTmp = null;
        boolean ok = false;
        try {
            Class<?> providerClass = Class.forName(PROVIDER_CLASS);
            Method get = providerClass.getMethod("get", new Class[0]);
            api = get.invoke(null, new Object[0]);
            if (api != null) {
                playerStorageTmp = SkinsRestorerSkinRepository.invoke(api, "getPlayerStorage", new Object[0]);
                try {
                    skinStorageTmp = SkinsRestorerSkinRepository.invoke(api, "getSkinStorage", new Object[0]);
                }
                catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // Current-only APIs can expose skin data through PlayerStorage.
                }
                ok = playerStorageTmp != null;
            }
        }
        catch (ClassNotFoundException e) {
            LOGGER.fine("SkinsRestorer not available, skin bridge will degrade gracefully.");
        }
        catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Failed to initialize SkinsRestorer API", e);
        }
        catch (LinkageError e) {
            LOGGER.log(Level.WARNING, "SkinsRestorer API is incompatible with this server, skin bridge will degrade gracefully.", e);
        }
        this.available = ok;
        this.playerStorage = playerStorageTmp;
        this.skinStorage = skinStorageTmp;
    }

    @Override
    public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
        if (!this.available || this.playerStorage == null) {
            return Optional.empty();
        }
        try {
            Optional<?> current = SkinsRestorerSkinRepository.tryCurrentApi(this.playerStorage, uuid, name);
            if (current != null && current.isPresent()) {
                Object data = current.get();
                String skinId = SkinsRestorerSkinRepository.optionalSkinIdentifier(this.playerStorage, uuid);
                return Optional.of(new SkinDto(uuid, name, skinId,
                    SkinsRestorerSkinRepository.readString(data, "getValue", "value"),
                    SkinsRestorerSkinRepository.readString(data, "getSignature", "signature"), null));
            }
            if (this.skinStorage == null) {
                return Optional.empty();
            }
            Optional skinId = SkinsRestorerSkinRepository.invokeOptional(this.playerStorage, "getSkinIdOfPlayer", uuid);
            if (skinId.isEmpty()) {
                return Optional.empty();
            }
            Optional skinData = SkinsRestorerSkinRepository.invokeOptional(this.skinStorage, "getSkinDataByIdentifier", skinId.get());
            if (skinData.isEmpty()) {
                return Optional.of(new SkinDto(uuid, name, SkinsRestorerSkinRepository.identifierOf(skinId.get()), null, null, null));
            }
            Object data = skinData.get();
            String value = SkinsRestorerSkinRepository.readString(data, "getValue", "value");
            String signature = SkinsRestorerSkinRepository.readString(data, "getSignature", "signature");
            return Optional.of(new SkinDto(uuid, name, SkinsRestorerSkinRepository.identifierOf(skinId.get()), value, signature, null));
        }
        catch (ReflectiveOperationException | ClassCastException | IllegalStateException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Failed to read skin for " + String.valueOf(uuid), e);
            return Optional.empty();
        }
    }

    @Override
    public void setSkinId(UUID uuid, String skinId) {
        if (!this.available || this.playerStorage == null) {
            return;
        }
        try {
            SkinsRestorerSkinRepository.setSkinIdentifier(this.playerStorage, uuid, skinId);
        }
        catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Failed to set skin id for " + String.valueOf(uuid), e);
        }
    }

    @Override
    public void setSkinData(UUID uuid, String value, String signature) {
        if (!this.available || this.playerStorage == null || this.skinStorage == null) {
            return;
        }
        try {
            Optional<Object> existing = SkinsRestorerSkinRepository.invokeOptional(this.playerStorage, "getSkinIdOfPlayer", uuid);
            String skinId = SkinsRestorerSkinRepository.skinIdForWrite(existing, uuid);
            SkinsRestorerSkinRepository.writeSkinData(this.skinStorage, skinId, value, signature);
            SkinsRestorerSkinRepository.setSkinIdentifier(this.playerStorage, uuid, skinId);
        }
        catch (ReflectiveOperationException | ClassCastException | IllegalStateException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Failed to set skin data for " + String.valueOf(uuid), e);
        }
    }

    @Override
    public void clearSkin(UUID uuid) {
        if (!this.available || this.playerStorage == null) {
            return;
        }
        try {
            SkinsRestorerSkinRepository.invokeVoid(this.playerStorage, "removeSkinIdOfPlayer", uuid);
        }
        catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Failed to clear skin for " + String.valueOf(uuid), e);
        }
    }

    private static Object invoke(Object target, String methodName, Object ... args) throws ReflectiveOperationException {
        if (target == null) {
            throw new NoSuchMethodException(methodName + " on null target");
        }
        Method method = SkinsRestorerSkinRepository.findMethod(target.getClass(), methodName, args);
        if (!method.trySetAccessible()) {
            throw new IllegalAccessException("Cannot access " + method);
        }
        return method.invoke(target, args);
    }

    private static void invokeVoid(Object target, String methodName, Object ... args) throws ReflectiveOperationException {
        SkinsRestorerSkinRepository.invoke(target, methodName, args);
    }

    private static void writeSkinData(Object storage, String skinId, String value, String signature) throws ReflectiveOperationException {
        try {
            SkinsRestorerSkinRepository.invokeVoid(storage, "setSkinData", skinId, value, signature);
            return;
        }
        catch (NoSuchMethodException ignored) {
            // SkinsRestorer 15.12 stores custom textures through SkinProperty.
        }
        Method selected = null;
        Object selectedProperty = null;
        int selectedScore = -1;
        String selectedKey = null;
        for (Method method : storage.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals("setCustomSkinData")
                || parameters.length != 2
                || SkinsRestorerSkinRepository.matchScore(parameters[0], skinId) < 0) continue;
            Object property;
            try {
                property = SkinsRestorerSkinRepository.skinPropertyFor(parameters[1], value, signature);
            }
            catch (ReflectiveOperationException | IllegalStateException ignored) {
                continue;
            }
            int score = SkinsRestorerSkinRepository.matchScore(parameters[0], skinId)
                + SkinsRestorerSkinRepository.matchScore(parameters[1], property);
            if (score < 0) continue;
            String key = SkinsRestorerSkinRepository.methodKey(method);
            if (selected == null || score > selectedScore || score == selectedScore && key.compareTo(selectedKey) < 0) {
                selected = method;
                selectedProperty = property;
                selectedScore = score;
                selectedKey = key;
            }
        }
        if (selected != null) {
            if (!selected.trySetAccessible()) {
                throw new IllegalAccessException("Cannot access " + selected);
            }
            selected.invoke(storage, skinId, selectedProperty);
            return;
        }
        throw new NoSuchMethodException("setSkinData or setCustomSkinData in " + storage.getClass());
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> invokeOptional(Object target, String methodName, Object ... args) throws ReflectiveOperationException {
        Object value = SkinsRestorerSkinRepository.invoke(target, methodName, args);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Optional<?> optional) {
            return (Optional<T>)optional;
        }
        return Optional.of((T)value);
    }

    private static Optional<?> tryCurrentApi(Object storage, UUID uuid, String name) throws ReflectiveOperationException {
        try {
            // The name overload may call Mojang for UUIDs that are not Mojang profiles.
            return SkinsRestorerSkinRepository.invokeOptional(storage, "getSkinOfPlayer", uuid);
        }
        catch (NoSuchMethodException ignored) {
            try {
                return SkinsRestorerSkinRepository.invokeOptional(storage, "getSkinForPlayer", uuid, name);
            }
            catch (NoSuchMethodException ignoredAgain) {
                return null;
            }
        }
    }

    private static String optionalSkinIdentifier(Object storage, UUID uuid) throws ReflectiveOperationException {
        try {
            Optional<?> value = SkinsRestorerSkinRepository.invokeOptional(storage, "getSkinIdOfPlayer", uuid);
            return value.isPresent() ? SkinsRestorerSkinRepository.identifierOf(value.get()) : null;
        }
        catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static String readString(Object target, String getter, String accessor) throws ReflectiveOperationException {
        Object value;
        try {
            value = SkinsRestorerSkinRepository.invoke(target, getter);
        }
        catch (NoSuchMethodException ignored) {
            value = SkinsRestorerSkinRepository.invoke(target, accessor);
        }
        if (value instanceof Optional<?> optional) {
            value = optional.isPresent() ? optional.get() : "";
        }
        return value == null ? "" : value.toString();
    }

    private static String skinIdForWrite(Optional<?> existing, UUID uuid) throws ReflectiveOperationException {
        if (existing.isPresent() && SkinsRestorerSkinRepository.isCustomIdentifier(existing.get())) {
            return SkinsRestorerSkinRepository.identifierOf(existing.get());
        }
        return "starx-" + uuid.toString().replace("-", "");
    }

    private static boolean isCustomIdentifier(Object value) throws ReflectiveOperationException {
        if (value instanceof String) {
            return true;
        }
        try {
            Object type = SkinsRestorerSkinRepository.invoke(value, "getSkinType");
            if (type == null) {
                return false;
            }
            String name = type instanceof Enum<?> enumValue ? enumValue.name() : type.toString();
            return "CUSTOM".equalsIgnoreCase(name);
        }
        catch (NoSuchMethodException ignored) {
            return true;
        }
    }

    private static String identifierOf(Object value) throws ReflectiveOperationException {
        if (value instanceof String identifier) {
            return identifier;
        }
        Object identifier = SkinsRestorerSkinRepository.invoke(value, "getIdentifier", new Object[0]);
        if (identifier instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("SkinsRestorer returned an invalid skin identifier");
    }

    private static void setSkinIdentifier(Object storage, UUID uuid, String skinId) throws ReflectiveOperationException {
        Method selected = null;
        Object selectedIdentifier = null;
        int selectedScore = -1;
        String selectedKey = null;
        for (Method method : storage.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals("setSkinIdOfPlayer") || parameters.length != 2) continue;
            int firstScore = SkinsRestorerSkinRepository.matchScore(parameters[0], uuid);
            if (firstScore < 0) continue;
            Object identifier;
            try {
                identifier = SkinsRestorerSkinRepository.skinIdentifierFor(skinId, parameters[1]);
            }
            catch (ReflectiveOperationException | IllegalStateException ignored) {
                continue;
            }
            int secondScore = SkinsRestorerSkinRepository.matchScore(parameters[1], identifier);
            if (secondScore < 0) continue;
            int score = firstScore + secondScore;
            String key = SkinsRestorerSkinRepository.methodKey(method);
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

    private static Object skinIdentifierFor(String skinId, Class<?> targetType) throws ReflectiveOperationException {
        if (SkinsRestorerSkinRepository.matchScore(targetType, skinId) >= 0) {
            return skinId;
        }
        for (String factoryName : new String[]{"ofCustom", "of"}) {
            try {
                Method factory = targetType.getMethod(factoryName, String.class);
                if (!Modifier.isStatic(factory.getModifiers()) || !factory.trySetAccessible()) continue;
                Object identifier = factory.invoke(null, skinId);
                if (targetType.isInstance(identifier)) {
                    return identifier;
                }
            }
            catch (ReflectiveOperationException ignored) {
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

    private static Object skinPropertyFor(Class<?> targetType, String value, String signature) throws ReflectiveOperationException {
        try {
            Method factory = targetType.getMethod("of", String.class, String.class);
            if (!Modifier.isStatic(factory.getModifiers()) || !factory.trySetAccessible()) {
                throw new NoSuchMethodException("Static SkinProperty factory is unavailable");
            }
            Object property = factory.invoke(null, value, signature);
            if (targetType.isInstance(property)) {
                return property;
            }
        }
        catch (ReflectiveOperationException ignored) {
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

    private static Method findMethod(Class<?> clazz, String name, Object ... args) throws NoSuchMethodException {
        Method selected = null;
        int selectedScore = -1;
        String selectedKey = null;
        for (Method method : clazz.getMethods()) {
            Class<?>[] paramTypes;
            if (!method.getName().equals(name) || (paramTypes = method.getParameterTypes()).length != args.length) continue;
            int score = 0;
            for (int i = 0; i < paramTypes.length; ++i) {
                int match = SkinsRestorerSkinRepository.matchScore(paramTypes[i], args[i]);
                if (match < 0) {
                    score = -1;
                    break;
                }
                score += match;
            }
            if (score < 0) continue;
            String key = SkinsRestorerSkinRepository.methodKey(method);
            if (selected == null || score > selectedScore || score == selectedScore && key.compareTo(selectedKey) < 0) {
                selected = method;
                selectedScore = score;
                selectedKey = key;
            }
        }
        if (selected != null) return selected;
        throw new NoSuchMethodException(name + " in " + String.valueOf(clazz));
    }

    private static int matchScore(Class<?> parameter, Object argument) {
        if (argument == null) {
            return parameter.isPrimitive() ? -1 : SkinsRestorerSkinRepository.typeSpecificity(parameter);
        }
        Class<?> wrapped = SkinsRestorerSkinRepository.wrap(parameter);
        Class<?> actual = argument.getClass();
        if (!wrapped.isAssignableFrom(actual)) {
            return -1;
        }
        int distance = SkinsRestorerSkinRepository.typeDistance(actual, wrapped);
        return distance == 0 ? 1000 : 100 - distance;
    }

    private static int typeDistance(Class<?> actual, Class<?> expected) {
        if (actual.equals(expected)) return 0;
        int distance = 32;
        Class<?> parent = actual.getSuperclass();
        if (parent != null && expected.isAssignableFrom(parent)) {
            distance = Math.min(distance, 1 + SkinsRestorerSkinRepository.typeDistance(parent, expected));
        }
        for (Class<?> interfaceType : actual.getInterfaces()) {
            if (expected.isAssignableFrom(interfaceType)) {
                distance = Math.min(distance, 1 + SkinsRestorerSkinRepository.typeDistance(interfaceType, expected));
            }
        }
        return distance;
    }

    private static int typeSpecificity(Class<?> type) {
        Class<?> wrapped = SkinsRestorerSkinRepository.wrap(type);
        int specificity = 1;
        Class<?> parent = wrapped.getSuperclass();
        if (parent != null) {
            specificity = Math.max(specificity, 1 + SkinsRestorerSkinRepository.typeSpecificity(parent));
        }
        for (Class<?> interfaceType : wrapped.getInterfaces()) {
            specificity = Math.max(specificity, 1 + SkinsRestorerSkinRepository.typeSpecificity(interfaceType));
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
        if (type == Boolean.TYPE) {
            return Boolean.class;
        }
        if (type == Integer.TYPE) {
            return Integer.class;
        }
        if (type == Long.TYPE) {
            return Long.class;
        }
        if (type == Double.TYPE) {
            return Double.class;
        }
        if (type == Byte.TYPE) {
            return Byte.class;
        }
        if (type == Short.TYPE) {
            return Short.class;
        }
        if (type == Float.TYPE) {
            return Float.class;
        }
        if (type == Character.TYPE) {
            return Character.class;
        }
        return type;
    }
}
