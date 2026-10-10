/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.module.skin;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
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
    private static final int PROFILE_CACHE_MAX_SIZE = 2000;
    private static final long PROFILE_CACHE_TTL_MS = Duration.ofDays(1).toMillis();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private final String skinProfileBaseUrl;
    private final Logger logger;
    private final HttpClient httpClient;
    private final Gson gson;
    private final TextureUrlPolicy textureUrlPolicy;
    private final java.util.LinkedHashMap<PlayerSkinKey, SkinDto> cache =
        new java.util.LinkedHashMap<>(CACHE_MAX_SIZE, 0.75f, true);
    private final java.util.LinkedHashMap<String, CachedProfile> profileCache =
        new java.util.LinkedHashMap<>(PROFILE_CACHE_MAX_SIZE, 0.75f, true);
    private final ProfileFallbackCache fallbackCache;
    private final java.util.Set<String> inFlightProfiles = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public WebsiteSkinRepository(String skinProfileBaseUrl, Logger logger) {
        this.skinProfileBaseUrl = skinProfileBaseUrl.endsWith("/") ? skinProfileBaseUrl.substring(0, skinProfileBaseUrl.length() - 1) : skinProfileBaseUrl;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
        this.gson = new Gson();
        this.textureUrlPolicy = TextureUrlPolicy.forWebsite(this.skinProfileBaseUrl);
        this.fallbackCache = new ProfileFallbackCache(Duration.ofHours(24));
    }

    @Override
    public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
        PlayerSkinKey key = new PlayerSkinKey(uuid, name);
        synchronized (this.cache) {
            SkinDto cached = this.cache.get(key);
            if (cached != null) return Optional.of(cached);
        }
        Optional<SkinDto> fetched = this.fetchSkin(uuid, name);
        fetched.ifPresent(skin -> {
            synchronized (this.cache) {
                this.cache.put(key, skin);
                trim(this.cache, CACHE_MAX_SIZE);
            }
        });
        return fetched;
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

    Optional<WebsiteSkinProfile> findProfile(UUID uuid, String name) {
        String cacheKey = uuid == null ? null : uuid.toString();
        return findProfileInternal(uuid, name, cacheKey, false);
    }

    Optional<WebsiteSkinProfile> findProfile(String name) {
        return findProfileInternal(null, name, normalizeName(name), false);
    }

    Optional<WebsiteSkinProfile> findProfile(String name, boolean forceRefresh) {
        return findProfileInternal(null, name, normalizeName(name), forceRefresh);
    }

    private Optional<WebsiteSkinProfile> findProfileInternal(
        UUID uuid,
        String name,
        String cacheKey,
        boolean forceRefresh
    ) {
        if (cacheKey == null || cacheKey.isBlank()) return Optional.empty();
        if (forceRefresh) {
            synchronized (this.profileCache) {
                this.profileCache.remove(cacheKey);
            }
        } else {
            synchronized (this.profileCache) {
                CachedProfile cached = this.profileCache.get(cacheKey);
                if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
                    return Optional.of(cached.profile());
                }
                if (cached != null) this.profileCache.remove(cacheKey);
            }
        }
        if (!this.inFlightProfiles.add(cacheKey)) return Optional.empty();
        try {
            Optional<WebsiteSkinProfile> profile = fetchProfile(uuid, name);
            profile.ifPresent(value -> {
                synchronized (this.profileCache) {
                    this.profileCache.put(cacheKey, new CachedProfile(
                        value, System.currentTimeMillis() + PROFILE_CACHE_TTL_MS, null));
                    trim(this.profileCache, PROFILE_CACHE_MAX_SIZE);
                }
            });
            return profile;
        } finally {
            this.inFlightProfiles.remove(cacheKey);
        }
    }

    void invalidate(UUID uuid) {
        if (uuid == null) return;
        synchronized (this.profileCache) {
            this.profileCache.remove(uuid.toString());
        }
    }

    void invalidate(String name) {
        String cacheKey = normalizeName(name);
        if (cacheKey != null) {
            synchronized (this.profileCache) {
                this.profileCache.remove(cacheKey);
            }
        }
    }

    private Optional<WebsiteSkinProfile> fetchProfile(UUID uuid, String name) {
        try {
            HttpResponse<String> response = fetchProfileResponse(uuid == null ? name : uuid.toString());
            if (response.statusCode() == 304 && uuid != null) {
                synchronized (this.profileCache) {
                    CachedProfile cached = this.profileCache.get(uuid.toString());
                    if (cached != null) return Optional.of(cached.profile());
                }
            }
            if (response.statusCode() == 404 && uuid != null) response = fetchProfileResponse(name);
            if (response.statusCode() == 404 || response.statusCode() == 304) return Optional.empty();
            if (response.statusCode() != 200) return fallbackCache.get(name, Instant.now());
            Optional<WebsiteSkinProfile> profile = WebsiteSkinProfile.parse(
                response.body(), this.gson, this.textureUrlPolicy);
            profile.ifPresent(value -> this.fallbackCache.put(name, value, Instant.now()));
            return profile;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception error) {
            this.logger.log(Level.FINE, "Website skin profile request failed for " + name, error);
            return fallbackCache.get(name, Instant.now());
        }
    }

    private HttpResponse<String> fetchProfileResponse(String key) throws Exception {
        String url = this.skinProfileBaseUrl + "/" + java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8) + ".json";
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT).GET();
        synchronized (this.profileCache) {
            CachedProfile cached = this.profileCache.get(key);
            if (cached != null && cached.etag() != null && !cached.etag().isBlank()) {
                builder.header("If-None-Match", cached.etag());
            }
        }
        HttpRequest request = builder.build();
        return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static <K, V> void trim(java.util.LinkedHashMap<K, V> cache, int maxSize) {
        while (cache.size() > maxSize) cache.remove(cache.keySet().iterator().next());
    }

    private record CachedProfile(WebsiteSkinProfile profile, long expiresAtMillis, String etag) { }
    private record PlayerSkinKey(UUID uuid, String name) { }
}
