package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.chunk.Dimension;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.file.BuiltInWorldFileType;
import io.github.addxiaoyi.starx.player.GameMode;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import io.github.addxiaoyi.starx.uworld.UworldWorldEditor;
import io.github.addxiaoyi.starx.uworld.UworldWorldGenerator;
import io.github.addxiaoyi.starx.velocity.config.UworldConfig;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

final class AuthUworldDefinition {

  private static final int FULL_SKY_LIGHT = 15;

  private final Path dataDirectory;
  private final UworldConfig.Auth config;
  private final Consumer<String> warningSink;
  private final Consumer<String> infoSink;

  AuthUworldDefinition(
      Path dataDirectory,
      UworldConfig.Auth config,
      Consumer<String> warningSink,
      Consumer<String> infoSink
  ) {
    this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
        .toAbsolutePath()
        .normalize();
    this.config = Objects.requireNonNull(config, "config");
    this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    this.infoSink = Objects.requireNonNull(infoSink, "infoSink");
  }

  UworldSpec spec() {
    UworldConfig.World world = this.config.world();
    if (!"ADVENTURE".equals(world.gameMode())) {
      this.warningSink.accept(
          "Authentication Uworld game mode is forced to ADVENTURE; configured value was "
              + world.gameMode());
    }
    return new UworldSpec(
        "auth",
        parseEnum(Dimension.class, world.dimension(), "dimension"),
        world.spawnX(),
        world.spawnY(),
        world.spawnZ(),
        world.spawnYaw(),
        world.spawnPitch(),
        GameMode.ADVENTURE,
        world.viewDistance(),
        world.simulationDistance(),
        30_000,
        6_000L);
  }

  UworldWorldGenerator generator() {
    return editor -> {
      UworldConfig.World world = this.config.world();
      Optional<BuiltInWorldFileType> loaded = this.loadConfiguredWorld(editor, world);
      if (loaded.isEmpty()) {
        generatePlatform(editor, world);
        int diameter = world.platformRadius() * 2 + 1;
        this.infoSink.accept(
            "Generated a " + diameter + "x" + diameter
                + " Uworld authentication platform");
      } else {
        this.infoSink.accept(
            "Loaded Uworld authentication world from " + loaded.get()
                + ": " + world.fileName());
      }
      editor.fillSkyLight(FULL_SKY_LIGHT);
    };
  }

  private Optional<BuiltInWorldFileType> loadConfiguredWorld(
      UworldWorldEditor editor,
      UworldConfig.World world) throws Exception {
    if ("VOID".equals(world.loaderType())) {
      return Optional.empty();
    }

    Path file = this.dataDirectory.resolve(world.fileName()).normalize();
    if (!file.startsWith(this.dataDirectory)) {
      throw new IllegalArgumentException("Uworld file escapes the StarX data directory: " + file);
    }
    if (!Files.isRegularFile(file)) {
      if ("AUTO".equals(world.loaderType())) {
        return Optional.empty();
      }
      this.warningSink.accept("Configured Uworld file is missing: " + file);
      throw new NoSuchFileException(file.toString());
    }
    Path realDataDirectory = this.dataDirectory.toRealPath();
    Path realFile = file.toRealPath();
    if (!realFile.startsWith(realDataDirectory)) {
      throw new IllegalArgumentException(
          "Uworld file resolves outside the StarX data directory: " + realFile);
    }

    BuiltInWorldFileType type = "AUTO".equals(world.loaderType())
        ? inferFileType(realFile)
        : parseEnum(BuiltInWorldFileType.class, world.loaderType(), "loader type");
    editor.load(
        type,
        realFile,
        world.offsetX(),
        world.offsetY(),
        world.offsetZ());
    return Optional.of(type);
  }

  private static BuiltInWorldFileType inferFileType(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".schem")) {
      return BuiltInWorldFileType.WORLDEDIT_SCHEM;
    }
    if (name.endsWith(".schematic")) {
      return BuiltInWorldFileType.SCHEMATIC;
    }
    if (name.endsWith(".nbt")) {
      return BuiltInWorldFileType.STRUCTURE;
    }
    if (name.endsWith(".litematic")) {
      return BuiltInWorldFileType.LITEMATIC;
    }
    throw new IllegalArgumentException(
        "AUTO Uworld loader only supports .schem, .schematic, .nbt, or .litematic: " + file);
  }

  private static void generatePlatform(UworldWorldEditor editor, UworldConfig.World world) {
    int centerX = (int) Math.floor(world.spawnX());
    int centerY = (int) Math.floor(world.spawnY()) - 1;
    int centerZ = (int) Math.floor(world.spawnZ());
    int radius = world.platformRadius();
    VirtualBlock stone = editor.createBlock("minecraft:stone");
    for (int x = centerX - radius; x <= centerX + radius; x++) {
      for (int z = centerZ - radius; z <= centerZ + radius; z++) {
        editor.setBlock(x, centerY, z, stone);
      }
    }
  }

  private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, String label) {
    try {
      return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Invalid Uworld " + label + ": " + value, error);
    }
  }
}
