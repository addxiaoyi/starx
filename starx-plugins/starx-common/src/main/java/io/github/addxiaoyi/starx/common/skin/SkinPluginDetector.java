/*
 * Copyright (c) 2024-2026 StarMC Team and contributors.
 * Use of this source code is governed by the MIT License.
 */
package io.github.addxiaoyi.starx.common.skin;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 皮肤插件检测器。
 * 自动检测服务器上安装的皮肤插件，并评估其兼容性。
 */
public final class SkinPluginDetector {

    private static final Logger LOGGER = Logger.getLogger(SkinPluginDetector.class.getName());

    // 已知的皮肤插件信息
    private static final List<PluginSignature> KNOWN_PLUGINS = List.of(
        // SkinsRestorer
        new PluginSignature(
            "SkinsRestorer",
            "net.skinsrestorer.api.SkinsRestorerProvider",
            "SkinsRestorer",
            "14.0",
            3
        ),
        // SkinsRestorer 13.x
        new PluginSignature(
            "SkinsRestorer",
            "net.skinsrestorer.api.SkinsRestorerProvider",
            "SkinsRestorer",
            "13.0",
            2
        ),
        // HDB (HeadDatabase)
        new PluginSignature(
            "HDB",
            "me.arcaniax.hdb.api.HeadDatabaseAPI",
            "HeadDatabase",
            "1.0",
            2
        ),
        // PicoSkin
        new PluginSignature(
            "PicoSkin",
            "com.ebrithil.picoskin.api.PicoSkinAPI",
            "PicoSkin",
            "1.0",
            2
        ),
        // SkinSystem
        new PluginSignature(
            "SkinSystem",
            "com.Blackixx.SkinSystem.api.SkinSystemAPI",
            "SkinSystem",
            "1.0",
            2
        ),
        // CMI (包含皮肤功能)
        new PluginSignature(
            "CMI",
            "com.Zrips.CMI.Modules.Skins.CMISkins",
            "CMI",
            "9.0",
            1
        ),
        // Multiverse-Inventories (可能包含皮肤功能)
        new PluginSignature(
            "Multiverse-Inventories",
            "com.onarandombox.multiverseinventories.api.skin.SkinAPI",
            "Multiverse-Inventories",
            "4.0",
            1
        )
    );

    private final ClassLoader classLoader;

    public SkinPluginDetector() {
        this(SkinPluginDetector.class.getClassLoader());
    }

    public SkinPluginDetector(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * 检测所有可用的皮肤插件
     */
    public List<SkinPluginInfo> detectAllPlugins() {
        List<SkinPluginInfo> detected = new ArrayList<>();

        // 检测已知插件
        for (PluginSignature signature : KNOWN_PLUGINS) {
            SkinPluginInfo info = detectPlugin(signature);
            if (info != null) {
                detected.add(info);
            }
        }

        // 检测未知插件（通过扫描类路径）
        List<SkinPluginInfo> unknown = detectUnknownPlugins();
        detected.addAll(unknown);

        // 按兼容性级别排序
        detected.sort((a, b) -> Integer.compare(b.getCompatibilityLevel(), a.getCompatibilityLevel()));

        return Collections.unmodifiableList(detected);
    }

    /**
     * 检测特定的插件
     */
    private SkinPluginInfo detectPlugin(PluginSignature signature) {
        try {
            Class<?> providerClass = Class.forName(signature.className(), true, classLoader);
            
            // 检查是否有 get 方法
            Method getMethod = providerClass.getMethod("get");
            Object api = getMethod.invoke(null);
            
            if (api != null) {
                // 检查是否有 getPlayerStorage 方法
                Method getPlayerStorageMethod = api.getClass().getMethod("getPlayerStorage");
                Object playerStorage = getPlayerStorageMethod.invoke(api);
                
                if (playerStorage != null) {
                    String version = getPluginVersion(signature);
                    return new SkinPluginInfo(
                        signature.pluginName(),
                        signature.className(),
                        version,
                        signature.compatibilityLevel(),
                        true
                    );
                }
            }
        } catch (ClassNotFoundException e) {
            // 插件未安装
            LOGGER.log(Level.FINE, "Plugin not found: " + signature.pluginName());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to detect plugin: " + signature.pluginName(), e);
        }
        
        return null;
    }

    /**
     * 获取插件版本
     */
    private String getPluginVersion(PluginSignature signature) {
        try {
            // 尝试从插件 JAR 获取版本
            Class<?> pluginClass = Class.forName(signature.className());
            ProtectionDomain protectionDomain = pluginClass.getProtectionDomain();
            CodeSource codeSource = protectionDomain.getCodeSource();
            
            if (codeSource != null) {
                URL location = codeSource.getLocation();
                if (location != null && location.getProtocol().equals("file")) {
                    File jarFile = new File(location.getPath());
                    return getVersionFromJar(jarFile);
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }
        
        return signature.defaultVersion();
    }

    /**
     * 从 JAR 文件中获取版本
     */
    private String getVersionFromJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            // 尝试从 plugin.yml 获取版本
            JarEntry pluginYml = jar.getJarEntry("plugin.yml");
            if (pluginYml != null) {
                try (InputStream is = jar.getInputStream(pluginYml)) {
                    Properties props = new Properties();
                    props.load(is);
                    return props.getProperty("version", "unknown");
                }
            }
            
            // 尝试从 pom.properties 获取版本
            JarEntry pomProps = jar.getJarEntry("META-INF/maven/com.example/plugin/pom.properties");
            if (pomProps != null) {
                try (InputStream is = jar.getInputStream(pomProps)) {
                    Properties props = new Properties();
                    props.load(is);
                    return props.getProperty("version", "unknown");
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }
        
        return "unknown";
    }

    /**
     * 检测未知的皮肤插件
     */
    private List<SkinPluginInfo> detectUnknownPlugins() {
        List<SkinPluginInfo> unknown = new ArrayList<>();
        
        try {
            // 获取类路径
            Enumeration<URL> resources = classLoader.getResources("");
            
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if (url.getProtocol().equals("file")) {
                    File file = new File(url.getPath());
                    if (file.isFile() && file.getName().endsWith(".jar")) {
                        SkinPluginInfo info = detectPluginFromJar(file);
                        if (info != null) {
                            unknown.add(info);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to detect unknown plugins", e);
        }
        
        return unknown;
    }

    /**
     * 从 JAR 文件中检测皮肤插件
     */
    private SkinPluginInfo detectPluginFromJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                
                // 检查是否是类文件
                if (name.endsWith(".class")) {
                    String className = name
                        .replace("/", ".")
                        .replace(".class", "");
                    
                    // 检查是否可能是皮肤插件 API
                    if (className.contains("skin") || className.contains("Skin")) {
                        try {
                            Class<?> clazz = Class.forName(className, true, classLoader);
                            
                            // 检查是否有 getPlayerStorage 方法
                            Method getPlayerStorage = clazz.getMethod("getPlayerStorage");
                            
                            // 检查是否有 get 方法
                            Method getMethod = clazz.getMethod("get");
                            Object api = getMethod.invoke(null);
                            
                            if (api != null) {
                                String version = getVersionFromJar(jarFile);
                                return new SkinPluginInfo(
                                    jarFile.getName(),
                                    className,
                                    version,
                                    1, // 未知插件兼容性级别为 1
                                    true
                                );
                            }
                        } catch (ClassNotFoundException e) {
                            // 类未找到，继续检查下一个
                        } catch (Exception e) {
                            // 忽略错误
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }
        
        return null;
    }

    /**
     * 检查特定的皮肤插件是否可用
     */
    public boolean isPluginAvailable(String pluginName) {
        return detectAllPlugins().stream()
            .anyMatch(info -> info.getPluginName().equalsIgnoreCase(pluginName));
    }

    /**
     * 获取特定皮肤插件的信息
     */
    public Optional<SkinPluginInfo> getPluginInfo(String pluginName) {
        return detectAllPlugins().stream()
            .filter(info -> info.getPluginName().equalsIgnoreCase(pluginName))
            .findFirst();
    }

    /**
     * 插件签名
     */
    private record PluginSignature(
        String pluginName,
        String className,
        String packageName,
        String defaultVersion,
        int compatibilityLevel
    ) {}

    /**
     * 插件信息
     */
    public static final class SkinPluginInfo {
        private final String pluginName;
        private final String className;
        private final String version;
        private final int compatibilityLevel;
        private final boolean available;

        public SkinPluginInfo(
            String pluginName,
            String className,
            String version,
            int compatibilityLevel,
            boolean available
        ) {
            this.pluginName = pluginName;
            this.className = className;
            this.version = version;
            this.compatibilityLevel = compatibilityLevel;
            this.available = available;
        }

        public String getPluginName() {
            return pluginName;
        }

        public String getClassName() {
            return className;
        }

        public String getVersion() {
            return version;
        }

        public int getCompatibilityLevel() {
            return compatibilityLevel;
        }

        public boolean isAvailable() {
            return available;
        }

        @Override
        public String toString() {
            return String.format("SkinPluginInfo[name=%s, class=%s, version=%s, compatibility=%d, available=%s]",
                pluginName, className, version, compatibilityLevel, available);
        }
    }
}
