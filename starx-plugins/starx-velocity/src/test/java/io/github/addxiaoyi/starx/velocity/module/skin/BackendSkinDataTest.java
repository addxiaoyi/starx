package io.github.addxiaoyi.starx.velocity.module.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.util.GameProfile;
import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BackendSkinDataTest {

  @Test
  void parsesResponseAndReplacesOnlyTextureProperty() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    BridgeMessage response = BridgeMessage.skinResponse(
        "factions",
        PlatformKind.PAPER,
        "skin-1",
        Map.of(
            "found", "true",
            "uuid", uuid.toString(),
            "name", "Alex",
            "provider", "skinsrestorer",
            "value", "new-value",
            "signature", "new-signature"));

    BackendSkinData skin = BackendSkinData.from(response).orElseThrow();
    List<GameProfile.Property> merged = skin.merge(List.of(
        new GameProfile.Property("textures", "old-value", "old-signature"),
        new GameProfile.Property("starx", "keep", "")));

    assertEquals("skinsrestorer", skin.provider());
    assertEquals(2, merged.size());
    assertTrue(merged.stream().anyMatch(property ->
        property.getName().equals("textures") && property.getValue().equals("new-value")));
    assertTrue(merged.stream().anyMatch(property -> property.getName().equals("starx")));
  }
}
