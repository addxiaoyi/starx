package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MigrationQueryContractTest {
  @Test
  void unknownSchemaModeFailsClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MigrationModule.buildStarVCQuery("typo", ""));
  }

  @Test
  void unsafeTablePrefixIsRejectedBeforeQueryConstruction() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MigrationModule.buildStarVCQuery("starx.starvc", "users; DROP TABLE starx_users;--"));
  }

  @Test
  void supportedModeKeepsTheConfiguredSimplePrefix() {
    assertEquals(
        "SELECT uuid, username, email, premium FROM legacy_starvc_users",
        MigrationModule.buildStarVCQuery("STARX.STARVC", "legacy_"));
  }

  @Test
  void schemaModeNormalizationRemovesConfigurationWhitespaceBeforeRowParsing() {
    assertEquals("starx.starvc", MigrationModule.normalizeSchemaMode(" STARX.STARVC "));
  }
}
