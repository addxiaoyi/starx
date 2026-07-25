package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class UworldConfigTest {

  @Test
  void normalizesNamesAndAcceptsEverySupportedLoader() {
    List<String> loaders = List.of("VOID", "SCHEMATIC", "WORLDEDIT_SCHEM", "STRUCTURE");

    assertAll(loaders.stream().map(loader -> () -> {
      UworldConfig.World world = validWorld("  " + loader.toLowerCase() + "  ");
      assertEquals("OVERWORLD", world.dimension());
      assertEquals("SURVIVAL", world.gameMode());
      assertEquals(loader, world.loaderType());
      assertEquals("auth_world.schem", world.fileName());
    }));
  }

  @Test
  void normalizesBlankAuthenticationTargetToLobby() {
    UworldConfig.Auth auth = new UworldConfig.Auth(300, "   ", validWorld("VOID"));

    assertEquals("lobby", auth.targetServer());
  }

  @Test
  void rejectsNonPositiveTimeouts() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class,
            () -> new UworldConfig(true, 0, validAuth(), validDiagnostics())),
        () -> assertThrows(IllegalArgumentException.class,
            () -> new UworldConfig.Auth(0, "lobby", validWorld("VOID"))),
        () -> assertThrows(IllegalArgumentException.class,
            () -> new UworldConfig.Diagnostics(false, 0, 5)));
  }

  @Test
  void validatesDistanceAndPlatformBounds() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithBounds(0, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithBounds(33, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithBounds(4, 0, 5)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithBounds(4, 33, 5)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithBounds(4, 4, 0)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithBounds(4, 4, 65)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> new UworldConfig.Diagnostics(false, 120, 0)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> new UworldConfig.Diagnostics(false, 120, 65)));
  }

  @Test
  void rejectsNonFiniteSpawnAndRotationValues() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithPose(Double.NaN, 100.0, 0.5, 0.0f, 0.0f)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithPose(0.5, Double.POSITIVE_INFINITY, 0.5, 0.0f, 0.0f)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithPose(0.5, 100.0, Double.NEGATIVE_INFINITY, 0.0f, 0.0f)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithPose(0.5, 100.0, 0.5, Float.NaN, 0.0f)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithPose(0.5, 100.0, 0.5, 0.0f, Float.POSITIVE_INFINITY)));
  }

  @Test
  void rejectsUnsupportedLoader() {
    assertThrows(IllegalArgumentException.class, () -> validWorld("CUSTOM"));
  }

  @Test
  void acceptsAWorldFileNestedBelowTheDataDirectory() {
    String nestedFile = Path.of("worlds", "auth.schem").toString();

    assertEquals(nestedFile, worldWithFile("WORLDEDIT_SCHEM", nestedFile).fileName());
  }

  @Test
  void rejectsBlankAbsoluteAndEscapingWorldFileNames() {
    String absoluteFile = Path.of("outside.schem").toAbsolutePath().toString();
    String escapingFile = Path.of("worlds", "..", "..", "outside.schem").toString();

    assertAll(
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithFile("VOID", "   ")),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithFile("SCHEMATIC", absoluteFile)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> worldWithFile("STRUCTURE", escapingFile)));
  }

  private static UworldConfig.Auth validAuth() {
    return new UworldConfig.Auth(300, "lobby", validWorld("VOID"));
  }

  private static UworldConfig.Diagnostics validDiagnostics() {
    return new UworldConfig.Diagnostics(false, 120, 5);
  }

  private static UworldConfig.World validWorld(String loader) {
    return new UworldConfig.World(
        " overworld ", 0.5, 100.0, 0.5, 0.0f, 0.0f,
        " survival ", loader, " auth_world.schem ",
        0, 0, 0, 4, 4, 5);
  }

  private static UworldConfig.World worldWithBounds(
      int viewDistance,
      int simulationDistance,
      int platformRadius
  ) {
    return new UworldConfig.World(
        "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
        "SURVIVAL", "VOID", "auth_world.schem",
        0, 0, 0, viewDistance, simulationDistance, platformRadius);
  }

  private static UworldConfig.World worldWithPose(
      double spawnX,
      double spawnY,
      double spawnZ,
      float spawnYaw,
      float spawnPitch
  ) {
    return new UworldConfig.World(
        "OVERWORLD", spawnX, spawnY, spawnZ, spawnYaw, spawnPitch,
        "SURVIVAL", "VOID", "auth_world.schem",
        0, 0, 0, 4, 4, 5);
  }

  private static UworldConfig.World worldWithFile(String loader, String fileName) {
    UworldConfig.World base = UworldConfig.World.defaults();
    return new UworldConfig.World(
        base.dimension(), base.spawnX(), base.spawnY(), base.spawnZ(),
        base.spawnYaw(), base.spawnPitch(), base.gameMode(), loader, fileName,
        base.offsetX(), base.offsetY(), base.offsetZ(), base.viewDistance(),
        base.simulationDistance(), base.platformRadius());
  }
}
