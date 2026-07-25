package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.addxiaoyi.starx.chunk.BuiltInBiome;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.file.BuiltInWorldFileType;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import io.github.addxiaoyi.starx.uworld.UworldWorldEditor;
import io.github.addxiaoyi.starx.velocity.config.UworldConfig;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AuthUworldDefinitionTest {

  @TempDir
  Path dataDirectory;

  @Test
  void defaultWorldGeneratesAnElevenByElevenPlatform() throws Exception {
    List<String> info = new ArrayList<>();
    AuthUworldDefinition definition = new AuthUworldDefinition(
        this.dataDirectory,
        UworldConfig.Auth.defaults(),
        message -> { },
        info::add);
    EditorProbe editor = new EditorProbe();

    UworldSpec spec = definition.spec();
    definition.generator().generate(editor);

    assertEquals("auth", spec.name());
    assertEquals(121, editor.blocks.size());
    assertEquals(15, editor.skyLight);
    assertTrue(editor.loads.isEmpty());
    assertEquals(List.of("Generated a 11x11 Uworld authentication platform"), info);
  }

  @Test
  void existingWorldFileUsesTheConfiguredLoader() throws Exception {
    List<String> info = new ArrayList<>();
    Path worldFile = Files.createFile(this.dataDirectory.resolve("auth.schem"));
    UworldConfig.World world = world("SCHEMATIC", worldFile.getFileName().toString(), 7);
    AuthUworldDefinition definition = new AuthUworldDefinition(
        this.dataDirectory,
        new UworldConfig.Auth(60, "lobby", world),
        message -> { },
        info::add);
    EditorProbe editor = new EditorProbe();

    definition.generator().generate(editor);

    assertEquals(List.of(BuiltInWorldFileType.SCHEMATIC), editor.loads);
    assertTrue(editor.blocks.isEmpty());
    assertEquals(List.of("Loaded Uworld authentication world from SCHEMATIC: auth.schem"), info);
  }

  @Test
  void missingWorldFileFailsBeforePublishingAPlatform() {
    List<String> warnings = new ArrayList<>();
    List<String> info = new ArrayList<>();
    UworldConfig.World world = world("STRUCTURE", "missing.nbt", 2);
    AuthUworldDefinition definition = new AuthUworldDefinition(
        this.dataDirectory,
        new UworldConfig.Auth(60, "lobby", world),
        warnings::add,
        info::add);
    EditorProbe editor = new EditorProbe();

    NoSuchFileException error = assertThrows(
        NoSuchFileException.class,
        () -> definition.generator().generate(editor));

    assertTrue(error.getFile().endsWith("missing.nbt"));
    assertTrue(editor.blocks.isEmpty());
    assertTrue(editor.loads.isEmpty());
    assertEquals(1, warnings.size());
    assertTrue(info.isEmpty());
  }

  @Test
  void linkedWorldFileCannotEscapeTheDataDirectory() throws Exception {
    Path externalFile = this.dataDirectory.resolveSibling(
        this.dataDirectory.getFileName() + "-outside.schem");
    Path linkedFile = this.dataDirectory.resolve("linked.schem");
    Files.createFile(externalFile);
    try {
      try {
        Files.createSymbolicLink(linkedFile, externalFile);
      } catch (IOException | UnsupportedOperationException | SecurityException error) {
        assumeTrue(false, "Symbolic links are unavailable: " + error.getClass().getSimpleName());
      }
      AuthUworldDefinition definition = new AuthUworldDefinition(
          this.dataDirectory,
          new UworldConfig.Auth(60, "lobby", world("SCHEMATIC", "linked.schem", 2)),
          message -> { },
          message -> { });
      EditorProbe editor = new EditorProbe();

      assertThrows(
          IllegalArgumentException.class,
          () -> definition.generator().generate(editor));
      assertTrue(editor.loads.isEmpty());
    } finally {
      Files.deleteIfExists(linkedFile);
      Files.deleteIfExists(externalFile);
    }
  }

  private static UworldConfig.World world(String loader, String fileName, int radius) {
    UworldConfig.World base = UworldConfig.World.defaults();
    return new UworldConfig.World(
        base.dimension(), base.spawnX(), base.spawnY(), base.spawnZ(),
        base.spawnYaw(), base.spawnPitch(), base.gameMode(), loader, fileName,
        1, 2, 3, base.viewDistance(), base.simulationDistance(), radius);
  }

  private static final class EditorProbe implements UworldWorldEditor {
    private final VirtualBlock block = (VirtualBlock) Proxy.newProxyInstance(
        VirtualBlock.class.getClassLoader(),
        new Class<?>[]{VirtualBlock.class},
        (instance, method, args) -> null);
    private final List<String> blocks = new ArrayList<>();
    private final List<BuiltInWorldFileType> loads = new ArrayList<>();
    private int skyLight = -1;

    @Override
    public VirtualBlock createBlock(String modernId) {
      return this.block;
    }

    @Override
    public void setBlock(int x, int y, int z, VirtualBlock block) {
      this.blocks.add(x + ":" + y + ":" + z);
    }

    @Override
    public void setBiome(int x, int y, int z, BuiltInBiome biome) {
    }

    @Override
    public void fillSkyLight(int level) {
      this.skyLight = level;
    }

    @Override
    public void fillBlockLight(int level) {
    }

    @Override
    public void load(
        BuiltInWorldFileType type,
        Path path,
        int offsetX,
        int offsetY,
        int offsetZ
    ) throws IOException {
      this.loads.add(type);
    }

    @Override
    public boolean isSealed() {
      return false;
    }
  }
}
