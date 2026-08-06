/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.skin;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import java.lang.reflect.Method;
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
            playerStorageTmp = SkinsRestorerSkinRepository.invoke(api, "getPlayerStorage", new Object[0]);
            skinStorageTmp = SkinsRestorerSkinRepository.invoke(api, "getSkinStorage", new Object[0]);
            ok = true;
        }
        catch (ClassNotFoundException e) {
            LOGGER.fine("SkinsRestorer not available, skin bridge will degrade gracefully.");
        }
        catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Failed to initialize SkinsRestorer API", e);
        }
        this.available = ok;
        this.playerStorage = playerStorageTmp;
        this.skinStorage = skinStorageTmp;
    }

    @Override
    public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
        if (!this.available) {
            return Optional.empty();
        }
        try {
            Optional skinId = SkinsRestorerSkinRepository.invokeOptional(this.playerStorage, "getSkinIdOfPlayer", uuid);
            if (skinId.isEmpty()) {
                return Optional.empty();
            }
            Optional skinData = SkinsRestorerSkinRepository.invokeOptional(this.skinStorage, "getSkinDataByIdentifier", skinId.get());
            if (skinData.isEmpty()) {
                return Optional.of(new SkinDto(uuid, name, SkinsRestorerSkinRepository.identifierOf(skinId.get()), null, null, null));
            }
            Object data = skinData.get();
            String value = (String)SkinsRestorerSkinRepository.invoke(data, "getValue", new Object[0]);
            String signature = (String)SkinsRestorerSkinRepository.invoke(data, "getSignature", new Object[0]);
            return Optional.of(new SkinDto(uuid, name, SkinsRestorerSkinRepository.identifierOf(skinId.get()), value, signature, null));
        }
        catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Failed to read skin for " + String.valueOf(uuid), e);
            return Optional.empty();
        }
    }

    @Override
    public void setSkinId(UUID uuid, String skinId) {
        if (!this.available) {
            return;
        }
        try {
            SkinsRestorerSkinRepository.setSkinIdentifier(this.playerStorage, uuid, skinId);
        }
        catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Failed to set skin id for " + String.valueOf(uuid), e);
        }
    }

    @Override
    public void setSkinData(UUID uuid, String value, String signature) {
        if (!this.available) {
            return;
        }
        try {
            Optional<Object> existing = SkinsRestorerSkinRepository.invokeOptional(this.playerStorage, "getSkinIdOfPlayer", uuid);
            String skinId = existing.map(SkinsRestorerSkinRepository::identifierOfUnchecked).orElseGet(() -> "starx-" + uuid.toString().replace("-", ""));
            SkinsRestorerSkinRepository.invokeVoid(this.skinStorage, "setSkinData", skinId, value, signature);
            SkinsRestorerSkinRepository.setSkinIdentifier(this.playerStorage, uuid, skinId);
        }
        catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Failed to set skin data for " + String.valueOf(uuid), e);
        }
    }

    @Override
    public void clearSkin(UUID uuid) {
        if (!this.available) {
            return;
        }
        try {
            SkinsRestorerSkinRepository.invokeVoid(this.playerStorage, "removeSkinIdOfPlayer", uuid);
        }
        catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Failed to clear skin for " + String.valueOf(uuid), e);
        }
    }

    private static Object invoke(Object target, String methodName, Object ... args) throws ReflectiveOperationException {
        Method method = SkinsRestorerSkinRepository.findMethod(target.getClass(), methodName, args);
        return method.invoke(target, args);
    }

    private static void invokeVoid(Object target, String methodName, Object ... args) throws ReflectiveOperationException {
        Method method = SkinsRestorerSkinRepository.findMethod(target.getClass(), methodName, args);
        method.invoke(target, args);
    }

    private static <T> Optional<T> invokeOptional(Object target, String methodName, Object ... args) throws ReflectiveOperationException {
        Method method = SkinsRestorerSkinRepository.findMethod(target.getClass(), methodName, args);
        return (Optional)method.invoke(target, args);
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

    private static String identifierOfUnchecked(Object value) {
        try {
            return SkinsRestorerSkinRepository.identifierOf(value);
        }
        catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to read SkinsRestorer skin identifier", exception);
        }
    }

    private static void setSkinIdentifier(Object storage, UUID uuid, String skinId) throws ReflectiveOperationException {
        for (Method method : storage.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals("setSkinIdOfPlayer") || parameters.length != 2 || !SkinsRestorerSkinRepository.wrap(parameters[0]).isInstance(uuid)) continue;
            method.invoke(storage, uuid, SkinsRestorerSkinRepository.skinIdentifierFor(skinId, parameters[1]));
            return;
        }
        throw new NoSuchMethodException("setSkinIdOfPlayer in " + storage.getClass());
    }

    private static Object skinIdentifierFor(String skinId, Class<?> targetType) throws ReflectiveOperationException {
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

    private static Method findMethod(Class<?> clazz, String name, Object ... args) throws NoSuchMethodException {
        for (Method method : clazz.getMethods()) {
            Class<?>[] paramTypes;
            if (!method.getName().equals(name) || (paramTypes = method.getParameterTypes()).length != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < paramTypes.length; ++i) {
                if (args[i] == null || SkinsRestorerSkinRepository.wrap(paramTypes[i]).isInstance(args[i])) continue;
                matches = false;
                break;
            }
            if (!matches) continue;
            return method;
        }
        throw new NoSuchMethodException(name + " in " + String.valueOf(clazz));
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
        return type;
    }
}
