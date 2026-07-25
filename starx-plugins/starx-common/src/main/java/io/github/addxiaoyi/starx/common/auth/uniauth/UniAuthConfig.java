/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth.uniauth;

import java.util.Objects;

public final class UniAuthConfig {
    private final boolean enabled;
    private final String apiUrl;
    private final String apiKey;
    private final int timeoutMs;
    private final boolean bridgeMode;

    public UniAuthConfig(boolean enabled, String apiUrl, String apiKey, int timeoutMs, boolean bridgeMode) {
        this.enabled = enabled;
        this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.timeoutMs = timeoutMs <= 0 ? 5000 : timeoutMs;
        this.bridgeMode = bridgeMode;
    }

    public static UniAuthConfig defaults() {
        return new UniAuthConfig(false, "https://api.example.com/uniauth/", "", 5000, false);
    }

    public boolean enabled() {
        return this.enabled;
    }

    public String apiUrl() {
        return this.apiUrl;
    }

    public String apiKey() {
        return this.apiKey;
    }

    public int timeoutMs() {
        return this.timeoutMs;
    }

    public boolean bridgeMode() {
        return this.bridgeMode;
    }

    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != this.getClass()) {
            return false;
        }
        UniAuthConfig that = (UniAuthConfig)obj;
        return this.enabled == that.enabled && Objects.equals(this.apiUrl, that.apiUrl) && Objects.equals(this.apiKey, that.apiKey) && this.timeoutMs == that.timeoutMs && this.bridgeMode == that.bridgeMode;
    }

    public int hashCode() {
        return Objects.hash(this.enabled, this.apiUrl, this.apiKey, this.timeoutMs, this.bridgeMode);
    }

    public String toString() {
        return "UniAuthConfig[enabled=" + this.enabled + ", apiUrl=" + this.apiUrl + ", apiKey=***, timeoutMs=" + this.timeoutMs + ", bridgeMode=" + this.bridgeMode + "]";
    }
}
