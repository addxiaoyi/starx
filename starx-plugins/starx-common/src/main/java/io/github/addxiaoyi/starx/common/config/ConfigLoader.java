/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.config;

import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.common.config.DatabaseConfig;
import io.github.addxiaoyi.starx.common.config.HttpApiConfig;
import io.github.addxiaoyi.starx.common.config.ModuleConfig;
import io.github.addxiaoyi.starx.common.config.StarxConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

public final class ConfigLoader {
    private static LoaderOptions createSecureOptions() {
        LoaderOptions opts = new LoaderOptions();
        // 限制别名在集合中的数量，防止 YAML BOMB 木马攻击和深度放大
        opts.setMaxAliasesForCollections(100);
        return opts;
    }
    private static final LoaderOptions SECURE_LOADER_OPTIONS = createSecureOptions();

    private ConfigLoader() {
    }

    public static StarxConfig load(Path path) throws IOException {
        Map<String, Object> root;
        if (!Files.isRegularFile(path, new LinkOption[0])) {
            return StarxConfig.defaults();
        }
        try (InputStream in = Files.newInputStream(path, new OpenOption[0]);){
            root = (Map<String, Object>)new Yaml(SECURE_LOADER_OPTIONS).load(in);
        }
        if (root == null) {
            root = Map.of();
        }
        HttpApiConfig httpApi = ConfigLoader.loadHttpApi(ConfigLoader.child(root, "http-api"));
        DatabaseConfig database = ConfigLoader.loadDatabase(ConfigLoader.child(root, "database"));
        UniAuthConfig uniauth = ConfigLoader.loadUniAuth(ConfigLoader.child(root, "uniauth"));
        Map<String, ModuleConfig> modules = ConfigLoader.loadModules(ConfigLoader.child(root, "modules"));
        return new StarxConfig(httpApi, database, uniauth, modules);
    }

    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof Map ? (Map)value : Map.of();
    }

    private static String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v != null ? v.toString() : def;
    }

    private static int integer(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number) {
            Number n = (Number)v;
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            }
            catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static long longVal(Map<String, Object> map, String key, long def) {
        Object v = map.get(key);
        if (v instanceof Number) {
            Number n = (Number)v;
            return n.longValue();
        }
        if (v != null) {
            try {
                return Long.parseLong(v.toString());
            }
            catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean)v : def;
    }

    private static UniAuthConfig loadUniAuth(Map<String, Object> node) {
        UniAuthConfig defaults = UniAuthConfig.defaults();
        UniAuthConfig.ProfileSyncConfig syncDefaults = defaults.profileSync();
        Map<String, Object> profileSync = ConfigLoader.child(node, "profile-sync");
        return new UniAuthConfig(
            ConfigLoader.bool(node, "enabled", defaults.enabled()),
            ConfigLoader.str(node, "api-url", defaults.apiUrl()),
            ConfigLoader.str(node, "api-key", defaults.apiKey()),
            ConfigLoader.integer(node, "timeout-ms", defaults.timeoutMs()),
            ConfigLoader.bool(node, "bridge-mode", defaults.bridgeMode()),
            new UniAuthConfig.ProfileSyncConfig(
                ConfigLoader.bool(profileSync, "enabled", syncDefaults.enabled()),
                ConfigLoader.bool(profileSync, "on-login", syncDefaults.onLogin()),
                ConfigLoader.bool(profileSync, "sync-email", syncDefaults.syncEmail()),
                ConfigLoader.bool(profileSync, "sync-external-user-id", syncDefaults.syncExternalUserId()),
                ConfigLoader.bool(profileSync, "overwrite-local-values", syncDefaults.overwriteLocalValues()),
                ConfigLoader.str(profileSync, "source-system", syncDefaults.sourceSystem())));
    }

    private static HttpApiConfig loadHttpApi(Map<String, Object> node) {
        HttpApiConfig defaults = HttpApiConfig.defaults();
        return new HttpApiConfig(ConfigLoader.str(node, "bind", defaults.bind()), ConfigLoader.integer(node, "port", defaults.port()), ConfigLoader.str(node, "api-key", defaults.apiKey()));
    }

    private static DatabaseConfig loadDatabase(Map<String, Object> node) {
        DatabaseConfig defaults = DatabaseConfig.defaults();
        return new DatabaseConfig(ConfigLoader.str(node, "type", defaults.type()), ConfigLoader.str(node, "host", defaults.host()), ConfigLoader.integer(node, "port", defaults.port()), ConfigLoader.str(node, "database", defaults.database()), ConfigLoader.str(node, "username", defaults.username()), ConfigLoader.str(node, "password", defaults.password()), ConfigLoader.str(node, "url", defaults.url()), ConfigLoader.integer(node, "pool-max-size", defaults.poolMaxSize()), ConfigLoader.longVal(node, "connection-timeout-ms", defaults.connectionTimeoutMs()), ConfigLoader.longVal(node, "pool-timeout-ms", defaults.poolTimeoutMs()));
    }

    private static Map<String, ModuleConfig> loadModules(Map<String, Object> node) {
        HashMap<String, ModuleConfig> modules = new HashMap<String, ModuleConfig>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String name = entry.getKey();
            boolean enabled = false;
            if (entry.getValue() instanceof Map) {
                enabled = ConfigLoader.bool((Map)entry.getValue(), "enabled", false);
            }
            modules.put(name, new ModuleConfig(enabled));
        }
        return modules;
    }
}
