package io.github.addxiaoyi.starx.limbo.server.item;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.network.ProtocolVersion;
import org.junit.jupiter.api.Test;

class SimpleItemComponentMapTest {
  @Test
  void removeKeepsTheFluentMapContract() {
    SimpleItemComponentMap map = new SimpleItemComponentMap(new SimpleItemComponentManager());

    assertSame(map, map.remove(ProtocolVersion.MINECRAFT_1_20_5, "minecraft:fire_resistant"));
    assertEquals(1, map.getRemoved().size());
  }
}
