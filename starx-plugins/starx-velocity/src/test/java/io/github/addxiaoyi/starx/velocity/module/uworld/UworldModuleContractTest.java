package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.uworld.UworldRuntime;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import org.junit.jupiter.api.Test;

final class UworldModuleContractTest {

  @Test
  void exposesOneUworldModuleAndPublicRuntime() {
    assertEquals("starx.uworld", UworldModule.MODULE_ID);
    assertTrue(VelocityModule.class.isAssignableFrom(UworldModule.class));
    assertTrue(UworldRuntime.class.isAssignableFrom(UworldModule.class));
  }

  @Test
  void ownsTheShutdownStartPhase() throws Exception {
    assertEquals(
        UworldModule.class,
        UworldModule.class.getMethod("onShutdownStart").getDeclaringClass());
  }
}
