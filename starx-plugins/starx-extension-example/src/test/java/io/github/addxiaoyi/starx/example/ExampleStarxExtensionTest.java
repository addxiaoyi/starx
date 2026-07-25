package io.github.addxiaoyi.starx.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionRegistration;
import io.github.addxiaoyi.starx.runtime.extension.DefaultStarxService;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExampleStarxExtensionTest {
  @Test
  void registersUsingOnlyThePublicExtensionContract() {
    DefaultStarxService service = new DefaultStarxService(
        "test", PlatformKind.VELOCITY, Set.of());
    ExampleStarxExtension extension = new ExampleStarxExtension();
    StarxExtensionRegistration registration = service.registerExtension(
        ExampleStarxExtension.descriptor("1.0.0"), extension);

    assertEquals(ExampleStarxExtension.EXTENSION_ID, registration.descriptor().id());
    assertEquals(1, extension.observedLifecycleEvents());
    assertTrue(service.extension(ExampleStarxExtension.EXTENSION_ID).isPresent());

    registration.close();
    assertTrue(service.extensions().isEmpty());
    service.close();
  }
}
