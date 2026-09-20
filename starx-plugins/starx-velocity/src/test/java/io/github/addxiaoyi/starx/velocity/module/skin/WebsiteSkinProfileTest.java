package io.github.addxiaoyi.starx.velocity.module.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import com.velocitypowered.api.util.GameProfile;
import org.junit.jupiter.api.Test;

final class WebsiteSkinProfileTest {

  private static final Gson GSON = new Gson();

  @Test
  void createsMinecraftTexturePropertyWithSkinCapeAndSlimModel() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    String body = """
        {
          "id": "4f06bce032d74d4dbb179f7e92ae8701",
          "name": "Alex",
          "textures": {
            "SKIN": {
              "url": "https://star-web.top/uploads/skin/alex.png",
              "metadata": { "model": "slim" }
            },
            "CAPE": { "url": "https://star-web.top/uploads/cape/star.png" }
          }
        }
        """;

    WebsiteSkinProfile profile = WebsiteSkinProfile.parse(
        body, GSON, TextureUrlPolicy.forWebsite("https://star-web.top/api/public/skin-profile"))
        .orElseThrow();
    String decoded = new String(
        Base64.getDecoder().decode(profile.textureValue(uuid, "Alex")),
        StandardCharsets.UTF_8);
    JsonObject textures = GSON.fromJson(decoded, JsonObject.class).getAsJsonObject("textures");

    assertEquals(
        "https://star-web.top/uploads/skin/alex.png",
        textures.getAsJsonObject("SKIN").get("url").getAsString());
    assertEquals(
        "slim",
        textures.getAsJsonObject("SKIN").getAsJsonObject("metadata").get("model").getAsString());
    assertEquals(
        "https://star-web.top/uploads/cape/star.png",
        textures.getAsJsonObject("CAPE").get("url").getAsString());
  }

  @Test
  void rejectsProfileWithoutSkinOrCape() {
    assertTrue(WebsiteSkinProfile.parse("{\"id\":\"empty\",\"textures\":{}}", GSON,
        TextureUrlPolicy.officialTexturesOnly()).isEmpty());
  }

  @Test
  void acceptsOnlyTheWebsiteProfileBoundToTheRequestedPlayer() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    WebsiteSkinProfile profile = WebsiteSkinProfile.parse("""
        { "id": "4f06bce032d74d4dbb179f7e92ae8701", "name": "Alex",
          "textures": { "SKIN": { "url": "https://example.invalid/alex.png" } } }
        """, GSON, TextureUrlPolicy.forWebsite("https://example.invalid/api/public/skin-profile"))
        .orElseThrow();

    assertTrue(profile.belongsTo(uuid, "alex"));
    assertFalse(profile.belongsTo(UUID.randomUUID(), "Alex"));
    assertFalse(profile.belongsTo(uuid, "Steve"));
  }

  @Test
  void createsExternalSkinOnlyForSafeHttpsTextureUrls() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");

    assertTrue(WebsiteSkinProfile.externalSkin(uuid, "Alex",
        "https://textures.minecraft.net/texture/alex").isPresent());
    assertTrue(WebsiteSkinProfile.externalSkin(uuid, "Alex",
        "http://textures.example.net/skin/alex.png").isEmpty());
    assertTrue(WebsiteSkinProfile.externalSkin(uuid, "Alex",
        "https://localhost/skin/alex.png").isEmpty());
    assertTrue(WebsiteSkinProfile.externalSkin(uuid, "Alex",
        "https://192.168.1.1/skin/alex.png").isEmpty());
  }

  @Test
  void preservesCurrentSkinWhenWebsiteProfileOnlySuppliesCape() {
    WebsiteSkinProfile profile = WebsiteSkinProfile.parse("""
        { "textures": { "CAPE": { "url": "https://star-web.top/uploads/cape/star.png" } } }
        """, new Gson(), TextureUrlPolicy.forWebsite(
            "https://star-web.top/api/public/skin-profile")).orElseThrow();
    UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    String currentValue = Base64.getEncoder().encodeToString("""
        { "textures": { "SKIN": { "url": "https://textures.minecraft.net/texture/current-skin" } } }
        """.getBytes(StandardCharsets.UTF_8));

    List<GameProfile.Property> merged = profile.merge(uuid, "Alex", List.of(
        new GameProfile.Property("textures", currentValue, "")));
    String decoded = new String(Base64.getDecoder().decode(merged.getFirst().getValue()), StandardCharsets.UTF_8);

    assertTrue(decoded.contains("current-skin"));
    assertTrue(decoded.contains("star.png"));
  }

  @Test
  void rejectsWebsiteTexturesOutsideTheConfiguredHosts() {
    assertTrue(WebsiteSkinProfile.parse("""
        { "textures": { "SKIN": { "url": "https://169.254.169.254/latest/meta-data" } } }
        """, GSON, TextureUrlPolicy.forWebsite("https://star-web.top/api/public/skin-profile"))
        .isEmpty());
    assertTrue(WebsiteSkinProfile.parse("""
        { "textures": { "SKIN": { "url": "https://textures.minecraft.net/texture/allowed" } } }
        """, GSON, TextureUrlPolicy.forWebsite("https://star-web.top/api/public/skin-profile"))
        .isPresent());
  }
}
