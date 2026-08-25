package io.github.addxiaoyi.starx.example;

import io.github.addxiaoyi.starx.api.extension.StarxApi;
import io.github.addxiaoyi.starx.api.extension.StarxCapabilities;
import io.github.addxiaoyi.starx.api.extension.StarxExtension;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionContext;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionDescriptor;
import io.github.addxiaoyi.starx.api.extension.StarxServiceEventTypes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal extension that depends only on the stable StarX public API. */
public final class ExampleStarxExtension implements StarxExtension {
  public static final String EXTENSION_ID = "io.github.addxiaoyi.starx.example";
  private final AtomicInteger observedLifecycleEvents = new AtomicInteger();

  public static StarxExtensionDescriptor descriptor(String implementationVersion) {
    return StarxExtensionDescriptor.create(
        EXTENSION_ID,
        "StarX Extension Example",
        implementationVersion);
  }

  @Override
  public void onEnable(StarxExtensionContext context) {
    context.subscribe(
        StarxServiceEventTypes.EXTENSION_ENABLED,
        event -> this.observedLifecycleEvents.incrementAndGet());
    context.publish(
        "ready",
        Map.of(
            "platform", context.platform().name(),
            "api", context.service().apiVersion().toString()));
    context.logger().log(
        System.Logger.Level.INFO,
        "STARX_EXTENSION_EXAMPLE=ENABLED platform=" + context.platform()
            + " api=" + context.service().apiVersion());
  }

  @Override
  public void onDisable(StarxExtensionContext context) {
    context.logger().log(
        System.Logger.Level.INFO,
        "STARX_EXTENSION_EXAMPLE=DISABLED platform=" + context.platform());
  }

  public int observedLifecycleEvents() {
    return this.observedLifecycleEvents.get();
  }
}
