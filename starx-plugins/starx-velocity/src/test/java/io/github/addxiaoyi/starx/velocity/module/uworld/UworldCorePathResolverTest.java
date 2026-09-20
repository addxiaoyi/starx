package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UworldCorePathResolverTest {

  private static final String NEW_SENTINEL = "new-core-sentinel";
  private static final String LEGACY_SENTINEL = "legacy-core-sentinel";

  @TempDir
  Path tempDir;

  @Test
  void neitherPathUsesNewDirectoryWithoutCreatingFiles() {
    List<String> warnings = new ArrayList<>();

    Path selected = UworldCorePathResolver.resolve(this.tempDir, warnings::add);

    assertAll(
        () -> assertEquals(this.tempDir.resolve("uworld"), selected),
        () -> assertTrue(warnings.isEmpty()),
        () -> assertFalse(Files.exists(this.tempDir.resolve("uworld"))),
        () -> assertFalse(Files.exists(this.tempDir.resolve("limbo"))));
  }

  @Test
  void newCoreUsesNewDirectoryAndLeavesFileUntouched() throws Exception {
    Path newCore = write("uworld", NEW_SENTINEL);
    List<String> warnings = new ArrayList<>();

    Path selected = UworldCorePathResolver.resolve(this.tempDir, warnings::add);

    assertAll(
        () -> assertEquals(this.tempDir.resolve("uworld"), selected),
        () -> assertTrue(warnings.isEmpty()),
        () -> assertEquals(NEW_SENTINEL, Files.readString(newCore, StandardCharsets.UTF_8)));
  }

  @Test
  void legacyCoreFallsBackOnceAndLeavesFileUntouched() throws Exception {
    Path legacyCore = write("limbo", LEGACY_SENTINEL);
    List<String> warnings = new ArrayList<>();

    Path selected = UworldCorePathResolver.resolve(this.tempDir, warnings::add);

    assertAll(
        () -> assertEquals(this.tempDir.resolve("limbo"), selected),
        () -> assertEquals(1, warnings.size()),
        () -> assertEquals(LEGACY_SENTINEL,
            Files.readString(legacyCore, StandardCharsets.UTF_8)),
        () -> assertFalse(Files.exists(this.tempDir.resolve("uworld"))));
  }

  @Test
  void bothCoreFilesUseNewDirectoryWithoutWarningsOrChanges() throws Exception {
    Path newCore = write("uworld", NEW_SENTINEL);
    Path legacyCore = write("limbo", LEGACY_SENTINEL);
    List<String> warnings = new ArrayList<>();

    Path selected = UworldCorePathResolver.resolve(this.tempDir, warnings::add);

    assertAll(
        () -> assertEquals(this.tempDir.resolve("uworld"), selected),
        () -> assertTrue(warnings.isEmpty()),
        () -> assertEquals(NEW_SENTINEL, Files.readString(newCore, StandardCharsets.UTF_8)),
        () -> assertEquals(LEGACY_SENTINEL,
            Files.readString(legacyCore, StandardCharsets.UTF_8)));
  }

  private Path write(String directory, String sentinel) throws Exception {
    Path core = this.tempDir.resolve(directory).resolve("core.yml");
    Files.createDirectories(core.getParent());
    Files.writeString(core, sentinel, StandardCharsets.UTF_8);
    return core;
  }
}
