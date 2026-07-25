package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class StarxConfigBackendBridgeTest {

  @Test
  void enablesBackendBridgeWhenUpgradedConfigHasNoEntry() {
    assertTrue(config(Map.of()).isModuleEnabled("starx.backend-bridge"));
  }

  @Test
  void honorsExplicitBackendBridgeDisable() {
    assertFalse(config(Map.of(
        "starx.backend-bridge",
        new StarxConfig.ModuleConfig(false)))
        .isModuleEnabled("starx.backend-bridge"));
  }

  private static StarxConfig config(Map<String, StarxConfig.ModuleConfig> modules) {
    return new StarxConfig(
        "",
        new StarxConfig.HttpConfig("127.0.0.1", 8788),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        modules);
  }
}
