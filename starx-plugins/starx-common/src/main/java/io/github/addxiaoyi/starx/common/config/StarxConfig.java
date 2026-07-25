/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.config;

import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.common.config.DatabaseConfig;
import io.github.addxiaoyi.starx.common.config.HttpApiConfig;
import io.github.addxiaoyi.starx.common.config.ModuleConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public record StarxConfig(HttpApiConfig httpApi, DatabaseConfig database, UniAuthConfig uniauth, Map<String, ModuleConfig> modules) {
    public StarxConfig {
        httpApi = httpApi == null ? HttpApiConfig.defaults() : httpApi;
        database = database == null ? DatabaseConfig.defaults() : database;
        uniauth = uniauth == null ? UniAuthConfig.defaults() : uniauth;
        modules = modules == null ? Map.of() : Map.copyOf(modules);
    }

    public static StarxConfig defaults() {
        return new StarxConfig(HttpApiConfig.defaults(), DatabaseConfig.defaults(), UniAuthConfig.defaults(), Map.of());
    }

    public StarxConfig merge(StarxConfig overlay) {
        Objects.requireNonNull(overlay, "overlay");
        HttpApiConfig mergedHttpApi = HttpApiConfig.defaults().equals(overlay.httpApi) ? this.httpApi : overlay.httpApi;
        DatabaseConfig mergedDatabase = DatabaseConfig.defaults().equals(overlay.database) ? this.database : overlay.database;
        UniAuthConfig mergedUniAuth = UniAuthConfig.defaults().equals(overlay.uniauth) ? this.uniauth : overlay.uniauth;
        HashMap<String, ModuleConfig> mergedModules = new HashMap<String, ModuleConfig>(this.modules);
        mergedModules.putAll(overlay.modules);
        return new StarxConfig(mergedHttpApi, mergedDatabase, mergedUniAuth, mergedModules);
    }

    public boolean isModuleEnabled(String name) {
        return this.modules.getOrDefault(name, new ModuleConfig(false)).enabled();
    }
}
