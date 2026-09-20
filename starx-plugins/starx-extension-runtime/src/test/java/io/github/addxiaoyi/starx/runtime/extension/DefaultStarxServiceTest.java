package io.github.addxiaoyi.starx.runtime.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.api.extension.ApiVersion;
import io.github.addxiaoyi.starx.api.extension.StarxApi;
import io.github.addxiaoyi.starx.api.extension.StarxExtension;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionContext;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionDescriptor;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionRegistration;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultStarxServiceTest {
  @Test
  void registersPublishesAndCleansUpExtensionLifecycle() {
    DefaultStarxService service = new DefaultStarxService(
        "test", PlatformKind.VELOCITY, Set.of("starx.test.capability"));
    List<String> events = new ArrayList<>();
    service.subscribe(StarxApi.ALL_EVENTS, event -> events.add(event.type()));
    AtomicInteger disables = new AtomicInteger();
    StarxExtensionRegistration registration = service.registerExtension(
        new StarxExtensionDescriptor(
            "example.test", "Example", "1.0.0", StarxApi.VERSION,
            Set.of("starx.test.capability"), List.of()),
        new StarxExtension() {
          @Override public void onEnable(StarxExtensionContext context) {
            context.publish("ready", Map.of("ok", true));
          }
          @Override public void onDisable(StarxExtensionContext context) {
            disables.incrementAndGet();
          }
        });
    assertEquals(StarxExtensionState.ENABLED, registration.snapshot().state());
    assertTrue(events.contains("extension.example.test.ready"));
    assertTrue(events.contains("starx.extension.enabled"));
    registration.close();
    registration.close();
    assertEquals(1, disables.get());
    assertFalse(service.extension("example.test").isPresent());
    service.close();
  }

  @Test
  void rejectsIncompatibleDuplicateAndMissingCapabilityExtensions() {
    DefaultStarxService service = new DefaultStarxService("test", PlatformKind.PAPER, Set.of());
    StarxExtension noop = context -> { };
    assertThrows(IllegalArgumentException.class, () -> service.registerExtension(
        new StarxExtensionDescriptor(
            "example.future", "Future", "1", new ApiVersion(2, 0, 0), Set.of(), List.of()), noop));
    assertThrows(IllegalArgumentException.class, () -> service.registerExtension(
        new StarxExtensionDescriptor(
            "example.missing", "Missing", "1", StarxApi.VERSION,
            Set.of("starx.missing"), List.of()), noop));
    StarxExtensionRegistration first = service.registerExtension(
        StarxExtensionDescriptor.create("example.duplicate", "Duplicate", "1"), noop);
    assertThrows(IllegalArgumentException.class, () -> service.registerExtension(
        StarxExtensionDescriptor.create("example.duplicate", "Duplicate", "2"), noop));
    first.close();
    service.close();
  }

  @Test
  void failedEnableRollsBackRegistration() {
    DefaultStarxService service = new DefaultStarxService("test", PlatformKind.FOLIA, Set.of());
    AtomicInteger cleanup = new AtomicInteger();
    assertThrows(IllegalStateException.class, () -> service.registerExtension(
        StarxExtensionDescriptor.create("example.failure", "Failure", "1"),
        new StarxExtension() {
          @Override public void onEnable(StarxExtensionContext context) {
            throw new IllegalStateException("boom");
          }
          @Override public void onDisable(StarxExtensionContext context) {
            cleanup.incrementAndGet();
          }
        }));
    assertEquals(1, cleanup.get());
    assertTrue(service.extensions().isEmpty());
    service.close();
  }
}
