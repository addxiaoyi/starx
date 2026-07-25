package io.github.addxiaoyi.starx.api.extension;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Removal or signature changes to the 1.0 public baseline must fail this test. */
class PublicApiCompatibilityTest {
  @Test
  void apiOneBaselineRemainsBinaryVisible() throws Exception {
    assertNotNull(StarxService.class.getMethod("apiVersion"));
    assertNotNull(StarxService.class.getMethod("implementationVersion"));
    assertNotNull(StarxService.class.getMethod("platform"));
    assertNotNull(StarxService.class.getMethod("capabilities"));
    assertNotNull(StarxService.class.getMethod(
        "registerExtension", StarxExtensionDescriptor.class, StarxExtension.class));
    assertNotNull(StarxService.class.getMethod("extension", String.class));
    assertNotNull(StarxService.class.getMethod("extensions"));
    assertNotNull(StarxService.class.getMethod("subscribe", String.class, Consumer.class));
    assertNotNull(StarxServiceProvider.class.getMethod("starxService"));
    assertNotNull(StarxServiceEventTypes.class.getField("EXTENSION_ENABLED"));
    assertNotNull(StarxServiceEventTypes.class.getField("BACKEND_READY"));
    assertNotNull(StarxExtensionContext.class.getMethod("platform"));
    assertNotNull(StarxExtensionContext.class.getMethod("capabilities"));
    assertNotNull(StarxExtensionContext.class.getMethod("publish", String.class, Map.class));
    assertNotNull(StarxExtensionDescriptor.class.getConstructor(
        String.class, String.class, String.class, ApiVersion.class, Set.class));
    assertNotNull(PlatformKind.class.getMethod("values"));
  }
}
