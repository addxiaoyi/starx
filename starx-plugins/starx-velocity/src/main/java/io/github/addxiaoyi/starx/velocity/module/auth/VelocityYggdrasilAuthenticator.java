/*
 * Copyright (c) 2024-2026 StarMC Team and contributors.
 * Use of this source code is governed by the MIT License.
 */
package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.common.auth.YggdrasilAuthenticator;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Yggdrasil 认证服务实现
 * 提供与 Mojang 正版认证服务的完整集成
 */
public class VelocityYggdrasilAuthenticator implements YggdrasilAuthenticator {

    private static final String MOJANG_AUTH_URL = "https://authserver.mojang.com/";
    private static final String MOJANG_SESSION_URL = "https://sessionserver.mojang.com/";
    
    private static final String USER_AGENT = "StarX/1.0";
    private static final String CLIENT_TOKEN = "starx-client";

    private final StarxVelocityPlugin plugin;
    private final YggdrasilModule.Config config;
    private final HttpClient httpClient;
    private final java.util.Map<String, String> authServers;

    public VelocityYggdrasilAuthenticator(StarxVelocityPlugin plugin, YggdrasilModule.Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.timeout()))
            .build();
        this.authServers = new java.util.HashMap<>(config.servers());
    }

    @Override
    public CompletableFuture<String> authenticate(String username, String serverId, String ip, String serverName) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String baseUrl = this.authServers.getOrDefault(serverName, MOJANG_SESSION_URL);
        String url = baseUrl + "session/minecraft/hasJoined"
            + "?username=" + encodeUrl(username)
            + "&serverId=" + encodeUrl(serverId);
        
        if (config.verifyIp() && ip != null) {
            url += "&ip=" + encodeUrl(ip);
        }

        this.httpClient.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("User-Agent", USER_AGENT)
                .build(), HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() == 200) {
                    future.complete(response.body());
                } else {
                    future.complete(null);
                }
            })
            .exceptionally(ex -> {
                plugin.logger().log(Level.FINE, "Yggdrasil authentication failed for " + username + ": " + ex.getMessage());
                future.complete(null);
                return null;
            });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> validateAccessToken(String username, String accessToken) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        String url = MOJANG_AUTH_URL + "validate";
        
        String jsonBody = String.format(
            "{\"accessToken\":\"%s\",\"clientToken\":\"%s\"}",
            accessToken, CLIENT_TOKEN);

        this.httpClient.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json")
                .build(), HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                future.complete(response.statusCode() == 204);
            })
            .exceptionally(ex -> {
                future.complete(false);
                return null;
            });
        return future;
    }

    @Override
    public CompletableFuture<UUID> getUuidByUsername(String username) {
        CompletableFuture<UUID> future = new CompletableFuture<>();
        String url = MOJANG_SESSION_URL + "session/minecraft/profile/" + encodeUrl(username);

        this.httpClient.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() == 200) {
                    try {
                        String body = response.body();
                        JsonElement id = JsonParser.parseString(body).getAsJsonObject().get("id");
                        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                            String value = id.getAsString().trim();
                            if (value.length() == 32) {
                                value = value.substring(0, 8) + "-"
                                    + value.substring(8, 12) + "-"
                                    + value.substring(12, 16) + "-"
                                    + value.substring(16, 20) + "-"
                                    + value.substring(20);
                            }
                            future.complete(UUID.fromString(value));
                        }
                    } catch (Exception e) {
                        plugin.logger().log(Level.WARNING, "Failed to parse UUID from username: " + username);
                    }
                }
                future.complete(null);
            })
            .exceptionally(ex -> {
                future.complete(null);
                return null;
            });
        return future;
    }

    @Override
    public CompletableFuture<String> getUsernameByUuid(UUID uuid) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String url = MOJANG_SESSION_URL + "session/minecraft/profile/" + uuid.toString().replace("-", "");

        this.httpClient.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() == 200) {
                    try {
                        String body = response.body();
                        JsonElement name = JsonParser.parseString(body).getAsJsonObject().get("name");
                        if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) {
                            future.complete(name.getAsString());
                        }
                    } catch (Exception e) {
                        plugin.logger().log(Level.WARNING, "Failed to parse username from UUID: " + uuid);
                    }
                }
                future.complete(null);
            })
            .exceptionally(ex -> {
                future.complete(null);
                return null;
            });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> isValidPremiumPlayer(UUID uuid) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        String url = MOJANG_SESSION_URL + "session/minecraft/profile/" + uuid.toString().replace("-", "");

        this.httpClient.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                future.complete(response.statusCode() == 200);
            })
            .exceptionally(ex -> {
                future.complete(false);
                return null;
            });
        return future;
    }

    @Override
    public CompletableFuture<String> refreshAccessToken(String accessToken, String clientToken) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String url = MOJANG_AUTH_URL + "refresh";
        
        String jsonBody = String.format(
            "{\"accessToken\":\"%s\",\"clientToken\":\"%s\",\"requestUser\":true}",
            accessToken, clientToken);

        this.httpClient.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json")
                .build(), HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() == 200) {
                    try {
                        JsonElement root = JsonParser.parseString(response.body());
                        JsonElement newToken = root.getAsJsonObject().get("accessToken");
                        if (newToken != null && newToken.isJsonPrimitive()) {
                            future.complete(newToken.getAsString());
                        }
                    } catch (Exception e) {
                        plugin.logger().log(Level.WARNING, "Failed to parse refreshed token");
                    }
                }
                future.complete(null);
            })
            .exceptionally(ex -> {
                future.complete(null);
                return null;
            });
        return future;
    }

    @Override
    public CompletableFuture<Void> invalidateAccessToken(String accessToken) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        String url = MOJANG_AUTH_URL + "invalidate";
        
        String jsonBody = String.format(
            "{\"accessToken\":\"%s\",\"clientToken\":\"%s\"}",
            accessToken, CLIENT_TOKEN);

        this.httpClient.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json")
                .build(), HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() == 200) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(
                        new RuntimeException("Failed to invalidate token: " + response.statusCode()));
                }
            })
            .exceptionally(ex -> {
                future.completeExceptionally(ex);
                return null;
            });
        return future;
    }

    private static String encodeUrl(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value.replace(" ", "%20");
        }
    }

    /**
     * 匹配 UUID 字符串（支持标准格式和无连字符格式）
     */
    static boolean matchesProfileUuid(String body, UUID expectedUuid) {
        if (body == null || expectedUuid == null) {
            return false;
        }
        try {
            JsonElement id = JsonParser.parseString(body).getAsJsonObject().get("id");
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                return false;
            }
            String value = id.getAsString().trim();
            if (value.length() == 32) {
                value = value.substring(0, 8) + "-"
                    + value.substring(8, 12) + "-"
                    + value.substring(12, 16) + "-"
                    + value.substring(16, 20) + "-"
                    + value.substring(20);
            }
            return UUID.fromString(value).equals(expectedUuid);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
