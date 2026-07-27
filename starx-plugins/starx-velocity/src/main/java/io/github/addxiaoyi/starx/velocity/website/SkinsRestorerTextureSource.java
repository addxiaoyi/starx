package io.github.addxiaoyi.starx.velocity.website;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import io.github.addxiaoyi.starx.website.PlayerTexture;
import io.github.addxiaoyi.starx.website.PlayerTextureRecord;
import io.github.addxiaoyi.starx.website.TextureBlob;
import io.github.addxiaoyi.starx.website.TextureKind;
import io.github.addxiaoyi.starx.website.TextureSource;
import io.github.addxiaoyi.starx.website.WebsiteSyncConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class SkinsRestorerTextureSource implements TextureSource {
  private static final int MAX_PLAYERS = 100_000;
  private static final int MAX_CACHE_ENTRIES = 2_048;

  record PlayerRef(UUID uuid, String name) {
    PlayerRef {
      uuid = Objects.requireNonNull(uuid, "uuid");
      name = Objects.requireNonNull(name, "name").trim();
    }
  }

  record TextureProfile(URI skin, URI cape, String model, Instant updatedAt) {
    TextureProfile {
      skin = Objects.requireNonNull(skin, "skin");
      model = Objects.requireNonNull(model, "model");
      updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
  }

  @FunctionalInterface
  interface TextureFetcher {
    byte[] fetch(URI uri) throws Exception;
  }

  private record CacheKey(TextureKind kind, URI uri) {
  }

  private final Supplier<? extends Collection<PlayerRef>> players;
  private final SkinRepository skins;
  private final TextureFetcher fetcher;
  private final Clock clock;
  private final Consumer<String> logger;
  private final Map<CacheKey, TextureBlob> cache = new ConcurrentHashMap<>();

  SkinsRestorerTextureSource(
      ProxyServer proxy,
      SkinRepository skins,
      WebsiteSyncConfig.Heartbeat heartbeat,
      Consumer<String> logger
  ) {
    this(
        () -> proxy.getAllPlayers().stream()
            .map(player -> new PlayerRef(player.getUniqueId(), player.getUsername()))
            .toList(),
        skins,
        httpFetcher(heartbeat),
        Clock.systemUTC(),
        logger);
  }

  SkinsRestorerTextureSource(
      Supplier<? extends Collection<PlayerRef>> players,
      SkinRepository skins,
      WebsiteSyncConfig.Heartbeat heartbeat,
      Consumer<String> logger
  ) {
    this(players, skins, httpFetcher(heartbeat), Clock.systemUTC(), logger);
  }

  SkinsRestorerTextureSource(
      Supplier<? extends Collection<PlayerRef>> players,
      SkinRepository skins,
      TextureFetcher fetcher,
      Clock clock,
      Consumer<String> logger
  ) {
    this.players = Objects.requireNonNull(players, "players");
    this.skins = Objects.requireNonNull(skins, "skins");
    this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.logger = logger == null ? ignored -> { } : logger;
  }

  static List<PlayerRef> mergePlayers(
      Collection<PlayerRef> historical,
      Collection<PlayerRef> online
  ) {
    LinkedHashMap<UUID, PlayerRef> merged = new LinkedHashMap<>();
    for (PlayerRef player : historical == null ? List.<PlayerRef>of() : historical) {
      if (player != null) merged.put(player.uuid(), player);
    }
    for (PlayerRef player : online == null ? List.<PlayerRef>of() : online) {
      if (player != null) merged.put(player.uuid(), player);
    }
    return merged.values().stream()
        .sorted(Comparator.comparing(player -> player.uuid().toString()))
        .limit(MAX_PLAYERS)
        .toList();
  }

  @Override
  public Collection<PlayerTextureRecord> snapshot() {
    List<PlayerRef> online = new ArrayList<>(this.players.get());
    online.sort(Comparator.comparing(player -> player.uuid().toString()));
    if (online.size() > MAX_PLAYERS) {
      online = new ArrayList<>(online.subList(0, MAX_PLAYERS));
    }

    List<PlayerTextureRecord> records = new ArrayList<>(online.size());
    int skipped = 0;
    for (PlayerRef player : online) {
      Optional<PlayerTextureRecord> record = load(player);
      if (record.isPresent()) {
        records.add(record.orElseThrow());
      } else {
        skipped++;
      }
    }
    if (skipped > 0) {
      this.logger.accept(
          "StarX website texture snapshot completed: players=" + online.size()
              + " emitted=" + records.size() + " skipped=" + skipped);
    }
    return List.copyOf(records);
  }

  private Optional<PlayerTextureRecord> load(PlayerRef player) {
    try {
      Optional<SkinDto> skin = this.skins.findByPlayer(player.uuid(), player.name());
      if (skin.isEmpty()) {
        return Optional.empty();
      }
      Optional<TextureProfile> profile = parseProfile(skin.orElseThrow(), this.clock.instant());
      if (profile.isEmpty()) {
        return Optional.empty();
      }
      TextureProfile texture = profile.orElseThrow();
      TextureBlob skinBlob = blob(TextureKind.SKIN, texture.skin());
      EnumMap<TextureKind, TextureBlob> blobs = new EnumMap<>(TextureKind.class);
      blobs.put(TextureKind.SKIN, skinBlob);
      String capeHash = null;
      if (texture.cape() != null) {
        try {
          TextureBlob capeBlob = blob(TextureKind.CAPE, texture.cape());
          blobs.put(TextureKind.CAPE, capeBlob);
          capeHash = capeBlob.sha256();
        } catch (Exception ignored) {
          // A broken optional cape must not suppress a valid skin.
        }
      }
      PlayerTexture manifest = new PlayerTexture(
          player.uuid().toString(),
          player.name(),
          skinBlob.sha256(),
          capeHash,
          texture.model(),
          "skinsrestorer",
          texture.updatedAt().toString(),
          false);
      return Optional.of(new PlayerTextureRecord(manifest, blobs));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private TextureBlob blob(TextureKind kind, URI uri) throws Exception {
    CacheKey key = new CacheKey(kind, uri);
    TextureBlob cached = this.cache.get(key);
    if (cached != null) {
      return cached;
    }
    byte[] bytes = this.fetcher.fetch(uri);
    TextureBlob loaded = new TextureBlob(kind, bytes, sha256(bytes));
    if (this.cache.size() >= MAX_CACHE_ENTRIES) {
      this.cache.clear();
    }
    TextureBlob previous = this.cache.putIfAbsent(key, loaded);
    return previous == null ? loaded : previous;
  }

  static Optional<TextureProfile> parseProfile(SkinDto skin, Instant fallbackUpdatedAt) {
    Objects.requireNonNull(skin, "skin");
    Objects.requireNonNull(fallbackUpdatedAt, "fallbackUpdatedAt");
    if (skin.textureUrl() != null && !skin.textureUrl().isBlank()) {
      return parseUri(skin.textureUrl())
          .map(uri -> new TextureProfile(uri, null, "classic", fallbackUpdatedAt));
    }
    if (skin.value() == null || skin.value().isBlank()) {
      return Optional.empty();
    }
    try {
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(skin.value());
      } catch (IllegalArgumentException standardFailure) {
        decoded = Base64.getUrlDecoder().decode(skin.value());
      }
      JsonElement parsed = JsonParser.parseString(
          new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
      if (!parsed.isJsonObject()) {
        return Optional.empty();
      }
      JsonObject root = parsed.getAsJsonObject();
      JsonObject textures = object(root, "textures");
      JsonObject skinNode = object(textures, "SKIN");
      Optional<URI> skinUri = parseUri(string(skinNode, "url"));
      if (skinUri.isEmpty()) {
        return Optional.empty();
      }
      String model = "classic";
      JsonObject metadata = optionalObject(skinNode, "metadata");
      if (metadata != null && "slim".equalsIgnoreCase(string(metadata, "model"))) {
        model = "slim";
      }
      URI cape = null;
      JsonObject capeNode = optionalObject(textures, "CAPE");
      if (capeNode != null) {
        cape = parseUri(string(capeNode, "url")).orElse(null);
      }
      Instant updatedAt = fallbackUpdatedAt;
      JsonElement timestamp = root.get("timestamp");
      if (timestamp != null && timestamp.isJsonPrimitive()
          && timestamp.getAsJsonPrimitive().isNumber()) {
        long epochMillis = timestamp.getAsLong();
        if (epochMillis > 0) {
          updatedAt = Instant.ofEpochMilli(epochMillis);
        }
      }
      return Optional.of(new TextureProfile(skinUri.orElseThrow(), cape, model, updatedAt));
    } catch (RuntimeException error) {
      return Optional.empty();
    }
  }

  static boolean isAllowedTextureUri(URI uri) {
    if (uri == null || uri.getScheme() == null || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getFragment() != null) {
      return false;
    }
    String scheme = uri.getScheme();
    if (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http")) {
      return false;
    }
    String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
    if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
      return false;
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(host)) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
          return false;
        }
      }
      return true;
    } catch (IOException error) {
      return false;
    }
  }

  private static TextureFetcher httpFetcher(WebsiteSyncConfig.Heartbeat heartbeat) {
    Objects.requireNonNull(heartbeat, "heartbeat");
    HttpClient http = HttpClient.newBuilder()
        .connectTimeout(heartbeat.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    Duration timeout = heartbeat.requestTimeout();
    return uri -> {
      if (!isAllowedTextureUri(uri)) {
        throw new IOException("Texture URL is not a public HTTP(S) endpoint");
      }
      HttpRequest request = HttpRequest.newBuilder(uri)
          .GET()
          .timeout(timeout)
          .header("Accept", "image/png")
          .build();
      HttpResponse<InputStream> response = http.send(
          request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        response.body().close();
        throw new IOException("Texture endpoint returned HTTP " + response.statusCode());
      }
      try (InputStream body = response.body()) {
        byte[] bytes = body.readNBytes(TextureBlob.MAX_BYTES + 1);
        if (bytes.length > TextureBlob.MAX_BYTES) {
          throw new IOException("Texture exceeds 512 KiB");
        }
        return bytes;
      }
    };
  }

  private static Optional<URI> parseUri(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      URI uri = URI.create(value.trim()).normalize();
      return uri.getScheme() == null || uri.getHost() == null
          ? Optional.empty() : Optional.of(uri);
    } catch (IllegalArgumentException error) {
      return Optional.empty();
    }
  }

  private static JsonObject object(JsonObject root, String key) {
    JsonObject value = optionalObject(root, key);
    if (value == null) {
      throw new IllegalArgumentException("Missing JSON object " + key);
    }
    return value;
  }

  private static JsonObject optionalObject(JsonObject root, String key) {
    JsonElement value = root.get(key);
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }

  private static String string(JsonObject root, String key) {
    JsonElement value = root.get(key);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
}
