package io.github.addxiaoyi.starx.velocity.module.skin;

import com.velocitypowered.api.util.GameProfile;
import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

record BackendSkinData(UUID uuid, String name, String provider, String value, String signature) {

  static Optional<BackendSkinData> from(BridgeMessage message) {
    if (!BridgeProtocol.SKIN_RESPONSE.equals(message.type())
        || !Boolean.parseBoolean(message.attributes().getOrDefault("found", "false"))) {
      return Optional.empty();
    }
    try {
      UUID uuid = UUID.fromString(message.attributes().getOrDefault("uuid", ""));
      String name = message.attributes().getOrDefault("name", "").trim();
      String provider = message.attributes().getOrDefault("provider", "").trim();
      String value = message.attributes().getOrDefault("value", "").trim();
      String signature = message.attributes().getOrDefault("signature", "");
      if (name.isEmpty() || provider.isEmpty() || value.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new BackendSkinData(uuid, name, provider, value, signature));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  List<GameProfile.Property> merge(List<GameProfile.Property> current) {
    ArrayList<GameProfile.Property> merged = new ArrayList<>();
    for (GameProfile.Property property : current) {
      if (!property.getName().equals("textures")) {
        merged.add(property);
      }
    }
    merged.add(new GameProfile.Property("textures", this.value, this.signature));
    return List.copyOf(merged);
  }
}
