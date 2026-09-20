package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationSurfaceContractTest {
  @Test
  void migrationModuleOnlyAdvertisesImplementedStarVcImport() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/MigrationModule.java"));

    assertTrue(source.contains("importStarVCMeta"));
    assertFalse(source.contains("public MigrationResult migrate(boolean"));
    assertFalse(source.contains("migrateFromMultiLogin"));
    assertFalse(source.contains("not implemented yet"));
    assertFalse(source.contains("isBeforeFirst()"));
    assertFalse(source.contains("public void onDisable() {\n        RUNNING.set(false);"));
    assertFalse(source.contains("catch (Exception ignored) {}"));
  }

  @Test
  void migrationCommandUsesStarxNamespaceAndRequiresAdminPermission() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/MigrationCommands.java"));

    assertTrue(source.contains("metaBuilder(\"sxmigrate\")"));
    assertTrue(source.contains("hasPermission(\"starx.admin.migrate\")"));
    assertFalse(source.contains("metaBuilder(\"authx\")"));
    assertFalse(source.contains("return true;"));
  }
}
