package io.github.addxiaoyi.starx.velocity.module.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
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

    WebsiteSkinProfile profile = WebsiteSkinProfile.parse(body, GSON).orElseThrow();
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
    assertTrue(WebsiteSkinProfile.parse("{\"id\":\"empty\",\"textures\":{}}", GSON).isEmpty());
  }
}
