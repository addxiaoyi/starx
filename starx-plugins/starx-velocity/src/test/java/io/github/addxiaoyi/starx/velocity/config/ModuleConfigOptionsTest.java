package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ModuleConfigOptionsTest {

  @Test
  void exposesTypedModuleOptionsWithoutDiscardingEnabled() {
    StarxConfig.ModuleConfig config = new StarxConfig.ModuleConfig(true, Map.of(
        "enabled", true,
        "debug", "true",
        "port", "19132",
        "action", "WARN",
        "servers", List.of("lobby", "survival")));

    assertTrue(config.enabled());
    assertTrue(config.booleanOption("debug", false));
    assertFalse(config.booleanOption("missing", false));
    assertEquals(19132, config.intOption("port", 0));
    assertEquals("WARN", config.stringOption("action", "ALLOW"));
    assertEquals(Set.of("lobby", "survival"), config.stringSet("servers", Set.of()));
  }
}
