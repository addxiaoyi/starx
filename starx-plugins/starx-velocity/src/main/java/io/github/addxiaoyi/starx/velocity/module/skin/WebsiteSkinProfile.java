package io.github.addxiaoyi.starx.velocity.module.skin;

import com.google.gson.Gson;
import com.velocitypowered.api.util.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class WebsiteSkinProfile {
  private final String id;
  private final String name;
  private final Map<String, TextureInfo> textures;
  private final Gson gson;

  private WebsiteSkinProfile(String id, String name, Map<String, TextureInfo> textures, Gson gson) {
    this.id = id;
    this.name = name;
    this.textures = Map.copyOf(textures);
    this.gson = gson;
  }

  static Optional<WebsiteSkinProfile> parse(String body, Gson gson) {
    if (body == null || body.isBlank()) {
      return Optional.empty();
    }
    ProfileResponse response = gson.fromJson(body, ProfileResponse.class);
    if (response == null || response.textures == null) {
      return Optional.empty();
    }
    Map<String, TextureInfo> textures = new LinkedHashMap<>();
    copyTexture(response.textures, textures, "SKIN");
    copyTexture(response.textures, textures, "CAPE");
    if (textures.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new WebsiteSkinProfile(response.id, response.name, textures, gson));
  }

  private static void copyTexture(
      Map<String, TextureInfo> source,
      Map<String, TextureInfo> target,
      String type) {
    TextureInfo texture = source.get(type);
    if (texture != null && texture.url != null && !texture.url.isBlank()) {
      target.put(type, texture);
    }
  }

  GameProfile.Property textureProperty(UUID uuid, String playerName) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("timestamp", System.currentTimeMillis());
    payload.put("profileId", profileId(uuid));
    payload.put("profileName", profileName(playerName));
    payload.put("textures", textures);
    String json = gson.toJson(payload);
    String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    return new GameProfile.Property("textures", value, "");
  }

  List<GameProfile.Property> merge(
      UUID uuid,
      String playerName,
      List<GameProfile.Property> current) {
    List<GameProfile.Property> merged = new ArrayList<>();
    for (GameProfile.Property property : current) {
      if (!property.getName().equals("textures")) {
        merged.add(property);
      }
    }
    merged.add(textureProperty(uuid, playerName));
    return List.copyOf(merged);
  }

  String textureUrl() {
    TextureInfo skin = textures.get("SKIN");
    return skin == null ? null : skin.url;
  }

  String id() {
    return id;
  }

  private String profileId(UUID uuid) {
    String candidate = id == null || id.isBlank() ? uuid.toString() : id;
    return candidate.replace("-", "");
  }

  private String profileName(String fallback) {
    return name == null || name.isBlank() ? fallback : name;
  }

  private static final class ProfileResponse {
    String id;
    String name;
    Map<String, TextureInfo> textures;
  }

  private static final class TextureInfo {
    String url;
    Map<String, String> metadata;
  }
}
