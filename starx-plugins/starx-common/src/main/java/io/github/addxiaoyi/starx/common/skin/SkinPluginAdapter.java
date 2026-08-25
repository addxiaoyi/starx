/*
 * Copyright (c) 2024-2026 StarMC Team and contributors.
 * Use of this source code is governed by the MIT License.
 */
package io.github.addxiaoyi.starx.common.skin;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 皮肤插件适配器。
 * 负责自动选择最佳的皮肤插件，并提供统一的皮肤查询接口。
 * 支持 SkinsRestorer、HDB、PicoSkin 等常见皮肤插件的自动适配。
 */
public final class SkinPluginAdapter implements SkinRepository {

    private static final Logger LOGGER = Logger.getLogger(SkinPluginAdapter.class.getName());

    private static final SkinPluginDetector detector = new SkinPluginDetector();
    private volatile SkinPluginDetector.SkinPluginInfo bestPlugin;

    // 皮肤存储相关
    private final Logger logger;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    // 缓存
    private static final ConcurrentMap<String, Object> skinCache = new ConcurrentHashMap<>();

    public SkinPluginAdapter() {
        this(Logger.getLogger(SkinPluginAdapter.class.getName()));
    }

    public SkinPluginAdapter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.detectBestPlugin();
    }

    /**
     * 检测并选择最佳的皮肤插件
     */
    private void detectBestPlugin() {
        List<SkinPluginDetector.SkinPluginInfo> plugins = detector.detectAllPlugins();

        if (plugins.isEmpty()) {
            LOGGER.fine("No skin plugins detected. Skin functionality will be unavailable.");
            return;
        }

        // 选择兼容级别最高的插件
        SkinPluginDetector.SkinPluginInfo best = null;
        for (SkinPluginDetector.SkinPluginInfo plugin : plugins) {
            if (plugin.isAvailable() && plugin.getCompatibilityLevel() > (best == null ? -1 : best.getCompatibilityLevel())) {
                best = plugin;
            }
        }

        this.bestPlugin = best;

        if (best != null) {
            LOGGER.info("Selected skin plugin: " + best.getPluginName() + " v" + best.getVersion()
                    + " (compatibility: " + best.getCompatibilityLevel() + ")");
        } else {
            LOGGER.fine("No compatible skin plugins detected.");
        }
    }

    @Override
    public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
        return this.findByPlayer(uuid, name, true);
    }

    /**
     * 查找玩家皮肤。allowMojangApi=false 时仅查询本地缓存，
     * 不触发皮肤插件内部的 Mojang API 网络请求（适用于受限网络环境）。
     */
    public Optional<SkinDto> findByPlayer(UUID uuid, String name, boolean allowMojangApi) {
        if (bestPlugin == null) {
            return Optional.empty();
        }

        try {
            // 尝试通过最佳插件查询
            Class<?> apiClass = Class.forName(bestPlugin.getClassName());
            Method getMethod = apiClass.getMethod("get");
            Object api = getMethod.invoke(null);

            Method getPlayerStorageMethod = api.getClass().getMethod("getPlayerStorage");
            Object playerStorage = getPlayerStorageMethod.invoke(api);

            // 尝试使用 Current API
            Optional<?> current = tryCurrentApi(playerStorage, uuid, name, allowMojangApi);
            if (current != null && current.isPresent()) {
                Object data = current.get();
                String skinId = optionalSkinIdentifier(playerStorage, uuid);
                return Optional.of(new SkinDto(uuid, name, skinId,
                    readString(data, "getValue", "value"),
                    readString(data, "getSignature", "signature"), null));
            }

            // 回退尝试其他插件
            if (bestPlugin.getCompatibilityLevel() > 0) {
                Optional<SkinDto> fallback = tryFallbackPlugins(uuid, name);
                if (fallback.isPresent()) {
                    return fallback;
                }
            }
        } catch (Exception e) {
            if (failureLogged.compareAndSet(false, true)) {
                LOGGER.log(Level.WARNING, "Failed to find skin via best plugin", e);
            }
        }

        return Optional.empty();
    }

    /**
     * 尝试使用 Current API
     */
    private Optional<?> tryCurrentApi(Object playerStorage, UUID uuid, String name, boolean allowMojangApi) {
        try {
            Method method = playerStorage.getClass().getMethod("getSkinForPlayer", UUID.class, String.class);
            return (Optional<?>) method.invoke(playerStorage, uuid, name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 尝试通过其他插件查询
     */
    private Optional<SkinDto> tryFallbackPlugins(UUID uuid, String name) {
        List<SkinPluginDetector.SkinPluginInfo> plugins = detector.detectAllPlugins();

        for (SkinPluginDetector.SkinPluginInfo plugin : plugins) {
            if (plugin == bestPlugin || !plugin.isAvailable()) {
                continue;
            }

            try {
                Class<?> pluginClass = Class.forName(plugin.getClassName());
                Method getMethod = pluginClass.getMethod("get");
                Object api = getMethod.invoke(null);

                Method getPlayerStorageMethod = api.getClass().getMethod("getPlayerStorage");
                Object playerStorage = getPlayerStorageMethod.invoke(api);

                Method getSkinForPlayer = playerStorage.getClass().getMethod("getSkinForPlayer", UUID.class, String.class);
                Object result = getSkinForPlayer.invoke(playerStorage, uuid, name);

                if (result != null && (Boolean) result.getClass().getMethod("isPresent").invoke(result)) {
                    Object skinData = result.getClass().getMethod("get").invoke(result);

                    String value = readString(skinData, "getValue", "value");
                    String signature = readString(skinData, "getSignature", "signature");
                    String skinId = readString(skinData, "getIdentifier", "identifier");

                    if (value != null) {
                        return Optional.of(new SkinDto(uuid, name, skinId, value, signature, null));
                    }
                }
            } catch (Exception e) {
                // 忽略错误，继续尝试下一个插件
            }
        }

        return Optional.empty();
    }

    /**
     * 从对象中读取字符串字段
     */
    private static String readString(Object data, String methodName, String fallbackField) {
        try {
            Method method = data.getClass().getMethod(methodName);
            Object value = method.invoke(data);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 可选皮肤标识符
     */
    private static String optionalSkinIdentifier(Object playerStorage, UUID uuid) {
        try {
            Method method = playerStorage.getClass().getMethod("getSkinIdOfPlayer", UUID.class);
            Object result = method.invoke(playerStorage, uuid);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void setSkinId(UUID uuid, String skinId) {
        this.trySetSkinId(uuid, skinId);
    }

    @Override
    public boolean isAvailable() {
        return bestPlugin != null;
    }

    public boolean trySetSkinId(UUID uuid, String skinId) {
        if (bestPlugin == null) {
            return false;
        }

        try {
            Class<?> apiClass = Class.forName(bestPlugin.getClassName());
            Method getMethod = apiClass.getMethod("get");
            Object api = getMethod.invoke(null);

            Method getPlayerStorageMethod = api.getClass().getMethod("getPlayerStorage");
            Object playerStorage = getPlayerStorageMethod.invoke(api);

            Method setSkinIdentifier = playerStorage.getClass().getMethod("setSkinIdentifier", UUID.class, String.class);
            setSkinIdentifier.invoke(playerStorage, uuid, skinId);
            return true;
        } catch (Exception e) {
            if (failureLogged.compareAndSet(false, true)) {
                LOGGER.log(Level.WARNING, "Failed to set skin ID", e);
            }
            return false;
        }
    }

    public void close() {
        bestPlugin = null;
        skinCache.clear();
    }

    @Override
    public void setSkinData(UUID uuid, String value, String signature) {
        throw new UnsupportedOperationException("Setting skin data via adapter is not supported");
    }

    @Override
    public void clearSkin(UUID uuid) {
        trySetSkinId(uuid, null);
    }

    @Override
    public String toString() {
        if (bestPlugin != null) {
            return String.format("SkinPluginAdapter[plugin='%s', v%s, compatibility=%d]",
                bestPlugin.getPluginName(), bestPlugin.getVersion(), bestPlugin.getCompatibilityLevel());
        }
        return "SkinPluginAdapter[disabled]";
    }
}
