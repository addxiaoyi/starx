/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthClient;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class UniAuthModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final UniAuthConfig config;
    private UniAuthClient client;

    public UniAuthModule(StarxVelocityPlugin plugin, EventBus eventBus, UniAuthConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.auth.uniauth";
    }

    @Override
    public void onEnable() {
        if (this.config.enabled()) {
            this.client = new UniAuthClient(this.config);
            this.plugin.logger().log(Level.INFO, "UniAuthModule \u5df2\u542f\u7528\uff0cAPI: {0}", this.config.apiUrl());
        }
    }

    @Override
    public void onDisable() {
        this.client = null;
    }

    public UniAuthConfig getConfig() {
        return this.config;
    }

    public UniAuthClient getClient() {
        return this.client;
    }

    public CompletableFuture<UniAuthClient.LoginResponse> login(String username, String password) {
        if (this.client == null) {
            return CompletableFuture.completedFuture(new UniAuthClient.LoginResponse(false, "UniAuth \u6a21\u5757\u672a\u542f\u7528", null, null));
        }
        return this.client.login(username, password);
    }

    public CompletableFuture<UniAuthClient.StatusResponse> fetchStatus(String username) {
        if (this.client == null) {
            return CompletableFuture.completedFuture(new UniAuthClient.StatusResponse(false, false, "disabled"));
        }
        return this.client.fetchStatus(username);
    }
}
