package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.junit.jupiter.api.Test;

final class BackendBridgeListenerContractTest {

  @Test
  void sendsHelloAfterTheProxyChannelIsRegistered() throws Exception {
    Method listener = BukkitBackendBridge.class.getDeclaredMethod(
        "onPlayerChannelRegistered",
        PlayerRegisterChannelEvent.class);

    assertNotNull(listener.getAnnotation(EventHandler.class));
    assertTrue(BukkitBackendBridge.isBridgeChannel("starx:bridge"));
    assertFalse(BukkitBackendBridge.isBridgeChannel("starx:main"));
  }
}
