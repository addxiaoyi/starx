package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HubCommandModuleTest {

  @Test
  void keepsTheHubModuleIdWithoutLimboBranding() {
    assertEquals("starx.hub", HubCommandModule.MODULE_ID);
    assertTrue(HubCommandModule.Config.defaultConfig().enabled());
    assertEquals("lobby", HubCommandModule.Config.defaultConfig().hubServerName());
  }
}
