package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.LimboFactory;
import io.github.addxiaoyi.starx.chunk.BuiltInBiome;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.chunk.VirtualWorld;
import io.github.addxiaoyi.starx.file.BuiltInWorldFileType;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class UworldWorldEditorImplTest {

  @Test
  void everyMutationFailsAfterPublication() {
    LimboFactory factory = proxy(LimboFactory.class);
    VirtualWorld world = proxy(VirtualWorld.class);
    VirtualBlock block = proxy(VirtualBlock.class);
    UworldWorldEditorImpl editor = new UworldWorldEditorImpl(factory, world);

    editor.seal();

    assertTrue(editor.isSealed());
    assertThrows(IllegalStateException.class, () -> editor.createBlock("minecraft:stone"));
    assertThrows(IllegalStateException.class, () -> editor.setBlock(0, 99, 0, block));
    assertThrows(IllegalStateException.class,
        () -> editor.setBiome(0, 100, 0, BuiltInBiome.PLAINS));
    assertThrows(IllegalStateException.class, () -> editor.fillSkyLight(15));
    assertThrows(IllegalStateException.class, () -> editor.fillBlockLight(0));
    assertThrows(IllegalStateException.class,
        () -> editor.load(BuiltInWorldFileType.SCHEMATIC, Path.of("world.schematic"), 0, 0, 0));
  }

  private static <T> T proxy(Class<T> type) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
        (instance, method, args) -> {
          throw new AssertionError("sealed editor touched " + method.getName());
        }));
  }
}
