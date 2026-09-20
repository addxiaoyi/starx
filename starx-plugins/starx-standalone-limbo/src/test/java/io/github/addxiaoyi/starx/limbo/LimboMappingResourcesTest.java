package io.github.addxiaoyi.starx.limbo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LimboMappingResourcesTest {

  private static final List<String> RUNTIME_MAPPINGS = List.of(
      "/mapping/blockentities_mapping.json",
      "/mapping/blocks_mapping.json",
      "/mapping/blocks.json",
      "/mapping/blockstates_mapping.json",
      "/mapping/blockstates.json",
      "/mapping/chat_type_1_19.nbt",
      "/mapping/chat_type_1_19_1.nbt",
      "/mapping/damage_type_1_19_4.nbt",
      "/mapping/damage_type_1_20.nbt",
      "/mapping/data_component_types_mapping.json",
      "/mapping/data_component_types.json",
      "/mapping/defaultblockproperties.json",
      "/mapping/fluids.json",
      "/mapping/items_mapping.json",
      "/mapping/items.json",
      "/mapping/legacyblocks.json",
      "/mapping/legacyitems.json",
      "/mapping/modern_block_id_remap.json",
      "/mapping/modern_item_id_remap.json",
      "/mapping/tags.json"
  );

  @Test
  void packagesEveryRuntimeMapping() throws Exception {
    for (String path : RUNTIME_MAPPINGS) {
      assertResource(path);
    }
  }

  private static void assertResource(String path) throws Exception {
    try (InputStream stream = LimboMappingResourcesTest.class.getResourceAsStream(path)) {
      assertNotNull(stream, () -> "Missing Limbo mapping resource: " + path);
      if (path.endsWith(".json")) {
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
          assertNotNull(JsonParser.parseReader(reader), () -> "Invalid Limbo JSON mapping: " + path);
        }
      } else {
        assertTrue(stream.read() >= 0, () -> "Empty Limbo binary mapping: " + path);
      }
    }
  }
}
