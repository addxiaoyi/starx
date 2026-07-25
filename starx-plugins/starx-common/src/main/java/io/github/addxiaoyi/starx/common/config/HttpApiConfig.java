/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.config;

public record HttpApiConfig(String bind, int port, String apiKey) {
    public HttpApiConfig {
        bind = bind == null || bind.isBlank() ? "0.0.0.0" : bind;
        port = port <= 0 ? 8080 : port;
        apiKey = apiKey == null ? "" : apiKey;
    }

    public static HttpApiConfig defaults() {
        return new HttpApiConfig("0.0.0.0", 8080, "");
    }
}
