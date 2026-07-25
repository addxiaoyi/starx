/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class YggdrasilModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final Config config;
    private final HttpClient httpClient;

    public YggdrasilModule(StarxVelocityPlugin plugin, EventBus eventBus, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeout())).build();
    }

    @Override
    public String name() {
        return "starx.auth.yggdrasil";
    }

    @Override
    public void onEnable() {
        this.plugin.logger().log(Level.INFO, "YggdrasilModule \u5df2\u542f\u7528\uff0c\u52a0\u8f7d {0} \u4e2a\u8ba4\u8bc1\u670d\u52a1\u5668", this.config.servers().size());
    }

    @Override
    public void onDisable() {
    }

    public Map<String, String> getServers() {
        return Collections.unmodifiableMap(this.config.servers());
    }

    public String resolveServerUrl(String serverName, String endpoint) {
        String baseUrl = this.config.servers().get(serverName);
        if (baseUrl == null) {
            return null;
        }
        return baseUrl + endpoint;
    }

    public CompletableFuture<Boolean> checkUserExists(String username, UUID uuid, String serverName) {
        CompletableFuture<Boolean> future = new CompletableFuture<Boolean>();
        String baseUrl = this.config.servers().get(serverName);
        if (baseUrl == null) {
            future.complete(false);
            return future;
        }
        String url = baseUrl + "session/minecraft/profile/" + uuid.toString().replace("-", "");
        ((CompletableFuture)this.httpClient.sendAsync(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() == 200) {
                String body = (String)response.body();
                boolean nameMatches = body.contains("\"name\"") && body.contains("\"" + username + "\"");
                future.complete(nameMatches);
            } else {
                future.complete(false);
            }
        })).exceptionally(ex -> {
            this.plugin.logger().log(Level.WARNING, "\u68c0\u67e5\u7528\u6237 " + username + " \u5728 " + serverName + " \u4e0a\u5931\u8d25: " + ((Throwable)ex).getMessage());
            future.complete(false);
            return null;
        });
        return future;
    }

    public CompletableFuture<Boolean> checkAllServers(String username, UUID uuid) {
        CompletableFuture<Boolean> result = new CompletableFuture<Boolean>();
        result.complete(false);
        return result;
    }

    public CompletableFuture<String> authenticate(String username, String serverId, String ip, String serverName) {
        CompletableFuture<String> future = new CompletableFuture<String>();
        String baseUrl = this.config.servers().get(serverName);
        if (baseUrl == null) {
            future.complete(null);
            return future;
        }
        StringBuilder urlBuilder = new StringBuilder(baseUrl).append("session/minecraft/hasJoined").append("?username=").append(username).append("&serverId=").append(serverId);
        if (this.config.verifyIp() && ip != null) {
            urlBuilder.append("&ip=").append(ip);
        }
        ((CompletableFuture)this.httpClient.sendAsync(HttpRequest.newBuilder().uri(URI.create(urlBuilder.toString())).GET().build(), HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() == 200) {
                future.complete((String)response.body());
            } else {
                future.complete(null);
            }
        })).exceptionally(ex -> {
            this.plugin.logger().log(Level.WARNING, "Yggdrasil \u8ba4\u8bc1 " + username + " \u5931\u8d25: " + ((Throwable)ex).getMessage());
            future.complete(null);
            return null;
        });
        return future;
    }

    public static interface Config {
        public boolean enabled();

        public Map<String, String> servers();

        public boolean verifyIp();

        public int timeout();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public Map<String, String> servers() {
                    return Map.of("mojang", "https://sessionserver.mojang.com/");
                }

                @Override
                public boolean verifyIp() {
                    return false;
                }

                @Override
                public int timeout() {
                    return 5000;
                }
            };
        }
    }
}
