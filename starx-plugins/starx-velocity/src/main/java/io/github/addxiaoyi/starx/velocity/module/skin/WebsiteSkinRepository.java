/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.module.skin;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import io.github.addxiaoyi.starx.common.smart.SmartCache;
import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WebsiteSkinRepository
implements SkinRepository {
    private static final int CACHE_TTL_MS = 60000;
    private static final int CACHE_MAX_SIZE = 500;
    private final String skinProfileBaseUrl;
    private final Logger logger;
    private final HttpClient httpClient;
    private final Gson gson;
    private final SmartCache<String, Optional<SkinDto>> cache;
    private final ProfileFallbackCache fallbackCache;

    public WebsiteSkinRepository(String skinProfileBaseUrl, Logger logger) {
        this.skinProfileBaseUrl = skinProfileBaseUrl.endsWith("/") ? skinProfileBaseUrl.substring(0, skinProfileBaseUrl.length() - 1) : skinProfileBaseUrl;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5L)).build();
        this.gson = new Gson();
        this.cache = new SmartCache<String, Optional<SkinDto>>(500, 60000L, k -> Optional.empty());
        this.fallbackCache = new ProfileFallbackCache(Duration.ofHours(24));
    }

    @Override
    public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
        Optional<SkinDto> cached = this.cache.getIfPresent(name);
        if (cached != null) {
            return cached;
        }
        Optional<SkinDto> fetched = this.fetchSkin(uuid, name);
        if (fetched.isPresent()) {
            this.cache.put(name, fetched);
            return fetched;
        }
        Optional<SkinDto> fallback = this.fallbackCache.get(name, Instant.now())
            .map(profile -> new SkinDto(uuid, name, profile.id(), null, null, profile.textureUrl()));
        this.cache.put(name, fallback);
        return fallback;
    }

    private Optional<SkinDto> fetchSkin(UUID uuid, String name) {
        String url = this.skinProfileBaseUrl + "/" + name + ".json";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(5L)).GET().build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return this.fallbackCache.get(name, Instant.now())
                    .map(profile -> new SkinDto(uuid, name, profile.id(), null, null, profile.textureUrl()));
            }
            Optional<WebsiteSkinProfile> profile = WebsiteSkinProfile.parse(response.body(), this.gson);
            profile.ifPresent(value -> this.fallbackCache.put(name, value, Instant.now()));
            return profile.map(value -> new SkinDto(uuid, name, value.id(), null, null, value.textureUrl()));
        }
        catch (Exception e) {
            this.logger.log(Level.WARNING, "Failed to fetch skin from website for " + name, e);
            return Optional.empty();
        }
    }

    @Override
    public void setSkinId(UUID uuid, String skinId) {
    }

    @Override
    public void setSkinData(UUID uuid, String value, String signature) {
    }

    @Override
    public void clearSkin(UUID uuid) {
    }

    Optional<WebsiteSkinProfile> findProfile(String name) {
        String url = this.skinProfileBaseUrl + "/" + name + ".json";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(5L)).GET().build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return this.fallbackCache.get(name, Instant.now());
            }
            Optional<WebsiteSkinProfile> profile = WebsiteSkinProfile.parse(response.body(), this.gson);
            profile.ifPresent(value -> this.fallbackCache.put(name, value, Instant.now()));
            return profile.or(() -> this.fallbackCache.get(name, Instant.now()));
        }
        catch (Exception e) {
            this.logger.log(Level.WARNING, "Failed to fetch website texture profile for " + name, e);
            return this.fallbackCache.get(name, Instant.now());
        }
    }
}
