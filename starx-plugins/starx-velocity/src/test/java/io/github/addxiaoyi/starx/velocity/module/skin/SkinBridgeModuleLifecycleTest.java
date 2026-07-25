package io.github.addxiaoyi.starx.velocity.module.skin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SkinBridgeModuleLifecycleTest {

  @Test
  void refreshesThePlayerSkinAfterARealBackendConnection() throws Exception {
    Class<?> listener = Arrays.stream(SkinBridgeModule.class.getDeclaredClasses())
        .filter(type -> type.getSimpleName().equals("Listener"))
        .findFirst()
        .orElseThrow();
    Method connected = listener.getDeclaredMethod(
        "onServerConnected",
        ServerConnectedEvent.class);

    assertNotNull(connected.getAnnotation(Subscribe.class));
  }

  @Test
  void requestsTheBackendSkinWhenOnlyTheBackendProviderIsAvailable() {
    assertTrue(SkinBridgeModule.shouldRefreshAfterConnect(true, false, false));
  }

  @Test
  void describesAConfirmedBackendSkinApplicationWithoutLeakingTheTexture() {
    UUID uuid = UUID.fromString("a77946af-36d5-3cf0-beb8-5b784f8498ed");

    assertTrue(SkinBridgeModule.backendSkinAppliedMessage(uuid, "skinsrestorer")
        .contains("provider=skinsrestorer"));
  }

  @Test
  void describesAnOfflineBackendSkinResponseWithoutLeakingItsValue() {
    UUID uuid = UUID.fromString("a77946af-36d5-3cf0-beb8-5b784f8498ed");
    String message = SkinBridgeModule.backendSkinResponseMessage(
        uuid, "skinsrestorer", 768, true);

    assertTrue(message.contains("provider=skinsrestorer"));
    assertTrue(message.contains("valueLength=768"));
    assertTrue(message.contains("found=true"));
  }
}
