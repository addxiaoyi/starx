package io.github.addxiaoyi.starx.velocity.module.integrations;

import io.github.addxiaoyi.starx.velocity.module.proxytools.ClientModProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class MapIntegrationCatalog {

  private static final Map<String, Integration> CLIENT_MODS = clientMods();

  private MapIntegrationCatalog() {
  }

  static Set<String> detectClientMaps(ClientModProfile profile) {
    Set<String> detected = new TreeSet<>();
    for (String modId : profile.modIds()) {
      String normalized = normalize(modId);
      Integration integration = CLIENT_MODS.get(normalized);
      if (integration != null) {
        detected.add(integration.id());
      }
    }
    return Set.copyOf(detected);
  }

  static List<Integration> supported() {
    return List.of(
        new Integration("journeymap", Kind.CLIENT_MAP, "Forge/NeoForge/Fabric 客户端地图"),
        new Integration("xaerominimap", Kind.CLIENT_MAP, "Xaero's Minimap"),
        new Integration("xaeroworldmap", Kind.CLIENT_MAP, "Xaero's World Map"),
        new Integration("voxelmap", Kind.CLIENT_MAP, "VoxelMap"),
        new Integration("bluemap", Kind.SERVER_WEB_MAP, "服务器 API/网页地图"),
        new Integration("dynmap", Kind.SERVER_WEB_MAP, "服务器 API/网页地图"),
        new Integration("squaremap", Kind.SERVER_WEB_MAP, "服务器 API/网页地图"),
        new Integration("pl3xmap", Kind.SERVER_WEB_MAP, "服务器 API/网页地图"),
        new Integration("openpac", Kind.CLAIM_MAP, "Open Parties and Claims / Xaero claims"),
        new Integration("ftbchunks", Kind.CLAIM_MAP, "FTB Chunks claims"));
  }

  private static Map<String, Integration> clientMods() {
    Map<String, Integration> result = new LinkedHashMap<>();
    for (Integration integration : supported()) {
      if (integration.kind() == Kind.CLIENT_MAP) {
        result.put(integration.id(), integration);
      }
    }
    result.put("xaeros_minimap", result.get("xaerominimap"));
    result.put("xaeros_world_map", result.get("xaeroworldmap"));
    result.put("voxel-map", result.get("voxelmap"));
    return Map.copyOf(result);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  enum Kind {
    CLIENT_MAP,
    SERVER_WEB_MAP,
    CLAIM_MAP
  }

  record Integration(String id, Kind kind, String description) {
  }
}
