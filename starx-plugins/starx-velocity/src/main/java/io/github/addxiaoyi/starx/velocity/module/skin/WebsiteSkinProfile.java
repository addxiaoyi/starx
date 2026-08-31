package io.github.addxiaoyi.starx.velocity.module.skin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

  static Optional<WebsiteSkinProfile> parse(String body, Gson gson, TextureUrlPolicy urlPolicy) {
    if (body == null || body.isBlank()) {
      return Optional.empty();
    }
    ProfileResponse response = gson.fromJson(body, ProfileResponse.class);
    if (response == null || response.textures == null) {
      return Optional.empty();
    }
    Map<String, TextureInfo> textures = new LinkedHashMap<>();
    copyTexture(response.textures, textures, "SKIN", urlPolicy);
    copyTexture(response.textures, textures, "CAPE", urlPolicy);
    if (textures.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new WebsiteSkinProfile(response.id, response.name, textures, gson));
  }

  static Optional<WebsiteSkinProfile> externalSkin(UUID uuid, String playerName, String skinUrl) {
    if (uuid == null || playerName == null || playerName.isBlank()
        || !TextureUrlPolicy.officialTexturesOnly().allows(skinUrl)) {
      return Optional.empty();
    }
    TextureInfo skin = new TextureInfo();
    skin.url = skinUrl.trim();
    return Optional.of(new WebsiteSkinProfile(
        uuid.toString(), playerName.trim(), Map.of("SKIN", skin), new Gson()));
  }

  private static void copyTexture(
      Map<String, TextureInfo> source,
      Map<String, TextureInfo> target,
      String type,
      TextureUrlPolicy urlPolicy) {
    TextureInfo texture = source.get(type);
    if (texture != null && urlPolicy.allows(texture.url)) {
      target.put(type, texture);
    }
  }

  String textureValue(UUID uuid, String playerName) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("timestamp", System.currentTimeMillis());
    payload.put("profileId", profileId(uuid));
    payload.put("profileName", profileName(playerName));
    payload.put("textures", textures);
    String json = gson.toJson(payload);
    return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  GameProfile.Property textureProperty(UUID uuid, String playerName) {
    return new GameProfile.Property("textures", textureValue(uuid, playerName), "");
  }

  List<GameProfile.Property> merge(
      UUID uuid,
      String playerName,
      List<GameProfile.Property> current) {
    JsonObject textures = currentTextures(current);
    for (Map.Entry<String, TextureInfo> entry : this.textures.entrySet()) {
      textures.add(entry.getKey(), this.gson.toJsonTree(entry.getValue()));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("timestamp", System.currentTimeMillis());
    payload.put("profileId", profileId(uuid));
    payload.put("profileName", profileName(playerName));
    payload.put("textures", textures);
    String value = Base64.getEncoder().encodeToString(
        this.gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
    List<GameProfile.Property> merged = new ArrayList<>();
    for (GameProfile.Property property : current) {
      if (!property.getName().equals("textures")) {
        merged.add(property);
      }
    }
    merged.add(new GameProfile.Property("textures", value, ""));
    return List.copyOf(merged);
  }

  private JsonObject currentTextures(List<GameProfile.Property> current) {
    for (GameProfile.Property property : current) {
      if (!property.getName().equals("textures")) continue;
      try {
        String decoded = new String(Base64.getDecoder().decode(property.getValue()), StandardCharsets.UTF_8);
        JsonElement root = JsonParser.parseString(decoded);
        JsonElement textures = root.isJsonObject() ? root.getAsJsonObject().get("textures") : null;
        if (textures != null && textures.isJsonObject()) return textures.getAsJsonObject().deepCopy();
      } catch (RuntimeException ignored) {
        // A malformed upstream property is replaced by the validated website profile.
      }
    }
    return new JsonObject();
  }

  String textureUrl() {
    TextureInfo skin = textures.get("SKIN");
    return skin == null ? null : skin.url;
  }

  String id() {
    return id;
  }

  boolean belongsTo(UUID uuid, String playerName) {
    if (uuid == null || playerName == null || playerName.isBlank()
        || id == null || id.isBlank() || name == null || name.isBlank()) {
      return false;
    }
    try {
      String compactId = id.replace("-", "");
      if (!compactId.matches("[0-9a-fA-F]{32}")) {
        return false;
      }
      UUID profileUuid = UUID.fromString(compactId.replaceFirst(
          "^(.{8})(.{4})(.{4})(.{4})(.{12})$", "$1-$2-$3-$4-$5"));
      return uuid.equals(profileUuid) && name.equalsIgnoreCase(playerName.trim());
    } catch (IllegalArgumentException ignored) {
      return false;
    }
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
