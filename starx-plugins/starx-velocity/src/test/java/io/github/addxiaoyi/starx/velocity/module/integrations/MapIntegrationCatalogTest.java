package io.github.addxiaoyi.starx.velocity.module.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.module.proxytools.ClientModProfile;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class MapIntegrationCatalogTest {

  @Test
  void detectsPopularClientMapModsAndPublishesServerCatalog() {
    ClientModProfile profile = new ClientModProfile(
        ClientModProfile.Loader.FABRIC,
        "fabric",
        Map.of(
            "journeymap", "1",
            "xaeros_minimap", "1",
            "voxel-map", "1"));

    assertEquals(
        Set.of("journeymap", "voxelmap", "xaerominimap"),
        MapIntegrationCatalog.detectClientMaps(profile));
    Set<String> supported = MapIntegrationCatalog.supported().stream()
        .map(MapIntegrationCatalog.Integration::id)
        .collect(java.util.stream.Collectors.toSet());
    assertTrue(supported.containsAll(Set.of(
        "bluemap", "dynmap", "squaremap", "pl3xmap", "openpac", "ftbchunks")));
  }
}
