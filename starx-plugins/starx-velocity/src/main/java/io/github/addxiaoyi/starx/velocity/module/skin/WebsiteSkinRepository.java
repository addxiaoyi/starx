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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WebsiteSkinRepository
implements SkinRepository {
    private static final int CACHE_TTL_MS = 60000;
    private static final int CACHE_MAX_SIZE = 500;
    private static final int PROFILE_CACHE_TTL_MS = 300000;
    private static final int PROFILE_CACHE_MAX_SIZE = 2000;
    private final String skinProfileBaseUrl;
    private final Logger logger;
    private final HttpClient httpClient;
    private final Gson gson;
    private final TextureUrlPolicy textureUrlPolicy;
    private final SmartCache<PlayerSkinKey, Optional<SkinDto>> cache;
    private final SmartCache<String, Optional<WebsiteSkinProfile>> profileCache;
    private final ProfileFallbackCache fallbackCache;

    public WebsiteSkinRepository(String skinProfileBaseUrl, Logger logger) {
        this.skinProfileBaseUrl = skinProfileBaseUrl.endsWith("/") ? skinProfileBaseUrl.substring(0, skinProfileBaseUrl.length() - 1) : skinProfileBaseUrl;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5L)).build();
        this.gson = new Gson();
        this.textureUrlPolicy = TextureUrlPolicy.forWebsite(this.skinProfileBaseUrl);
        this.cache = new SmartCache<PlayerSkinKey, Optional<SkinDto>>(
            CACHE_MAX_SIZE, CACHE_TTL_MS, key -> Optional.empty());
        this.profileCache = new SmartCache<String, Optional<WebsiteSkinProfile>>(
            PROFILE_CACHE_MAX_SIZE, PROFILE_CACHE_TTL_MS, key -> Optional.empty());
        this.fallbackCache = new ProfileFallbackCache(Duration.ofHours(24));
    }

    @Override
    public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
        PlayerSkinKey cacheKey = new PlayerSkinKey(uuid, name);
        Optional<SkinDto> cached = this.cache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        Optional<SkinDto> fetched = this.fetchSkin(uuid, name);
        if (fetched.isPresent()) {
            this.cache.put(cacheKey, fetched);
            return fetched;
        }
        Optional<SkinDto> fallback = this.fallbackCache.get(name, Instant.now())
            .map(profile -> new SkinDto(uuid, name, profile.id(), null, null, profile.textureUrl()));
        this.cache.put(cacheKey, fallback);
        return fallback;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    private Optional<SkinDto> fetchSkin(UUID uuid, String name) {
        return this.findProfile(name)
            .map(profile -> new SkinDto(uuid, name, profile.id(), null, null, profile.textureUrl()));
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

    @Override
    public boolean trySetSkinId(UUID uuid, String skinId) {
        return false;
    }

    @Override
    public boolean trySetSkinData(UUID uuid, String value, String signature) {
        return false;
    }

    @Override
    public boolean tryClearSkin(UUID uuid) {
        return false;
    }

    Optional<WebsiteSkinProfile> findProfile(String name) {
        return this.findProfile(name, false);
    }

    Optional<WebsiteSkinProfile> findProfile(String name, boolean forceRefresh) {
        String cacheKey = normalizeName(name);
        if (cacheKey == null) {
            return Optional.empty();
        }
        if (forceRefresh) {
            this.profileCache.remove(cacheKey);
        } else {
            Optional<WebsiteSkinProfile> cached = this.profileCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        Optional<WebsiteSkinProfile> profile = this.fetchProfile(name);
        this.profileCache.put(cacheKey, profile);
        return profile;
    }

    private Optional<WebsiteSkinProfile> fetchProfile(String name) {
        String url = this.skinProfileBaseUrl + "/" + name + ".json";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(5L)).GET().build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return this.fallbackCache.get(name, Instant.now());
            }
            Optional<WebsiteSkinProfile> profile = WebsiteSkinProfile.parse(
                response.body(), this.gson, this.textureUrlPolicy);
            profile.ifPresent(value -> this.fallbackCache.put(name, value, Instant.now()));
            return profile.or(() -> this.fallbackCache.get(name, Instant.now()));
        }
        catch (Exception e) {
            this.logger.log(Level.WARNING, "Failed to fetch website texture profile for " + name, e);
            return this.fallbackCache.get(name, Instant.now());
        }
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private record PlayerSkinKey(UUID uuid, String name) { }
}
