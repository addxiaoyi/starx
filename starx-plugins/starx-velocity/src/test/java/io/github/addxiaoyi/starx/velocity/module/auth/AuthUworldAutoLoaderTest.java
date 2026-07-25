package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.chunk.BuiltInBiome;
import io.github.addxiaoyi.starx.file.BuiltInWorldFileType;
import io.github.addxiaoyi.starx.uworld.UworldWorldEditor;
import io.github.addxiaoyi.starx.velocity.config.UworldConfig;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AuthUworldAutoLoaderTest {

  @TempDir Path dataDirectory;

  @Test
  void autoLoaderUsesSpongeSchematicWhenPresent() throws Exception {
    Files.createFile(this.dataDirectory.resolve("auth_world.schem"));
    Probe editor = new Probe();
    List<String> info = new ArrayList<>();
    AuthUworldDefinition definition = new AuthUworldDefinition(
        this.dataDirectory,
        UworldConfig.Auth.defaults(),
        ignored -> { },
        info::add);

    definition.generator().generate(editor);

    assertEquals(List.of(BuiltInWorldFileType.WORLDEDIT_SCHEM), editor.loads);
    assertTrue(editor.blocks.isEmpty());
    assertEquals(
        List.of("Loaded Uworld authentication world from WORLDEDIT_SCHEM: auth_world.schem"),
        info);
  }

  @Test
  void autoLoaderFallsBackToElevenByElevenPlatformWhenFileIsAbsent() throws Exception {
    Probe editor = new Probe();
    AuthUworldDefinition definition = new AuthUworldDefinition(
        this.dataDirectory,
        UworldConfig.Auth.defaults(),
        ignored -> { },
        ignored -> { });

    definition.generator().generate(editor);

    assertTrue(editor.loads.isEmpty());
    assertEquals(121, editor.blocks.size());
  }

  private static final class Probe implements UworldWorldEditor {
    private final VirtualBlock block = (VirtualBlock) Proxy.newProxyInstance(
        VirtualBlock.class.getClassLoader(),
        new Class<?>[]{VirtualBlock.class},
        (instance, method, args) -> null);
    private final List<String> blocks = new ArrayList<>();
    private final List<BuiltInWorldFileType> loads = new ArrayList<>();

    @Override public VirtualBlock createBlock(String modernId) { return this.block; }
    @Override public void setBlock(int x, int y, int z, VirtualBlock block) {
      this.blocks.add(x + ":" + y + ":" + z);
    }
    @Override public void setBiome(int x, int y, int z, BuiltInBiome biome) { }
    @Override public void fillSkyLight(int level) { }
    @Override public void fillBlockLight(int level) { }
    @Override public void load(BuiltInWorldFileType type, Path path, int x, int y, int z)
        throws IOException { this.loads.add(type); }
    @Override public boolean isSealed() { return false; }
  }
}
