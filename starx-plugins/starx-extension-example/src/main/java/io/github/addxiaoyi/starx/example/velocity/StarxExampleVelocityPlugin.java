package io.github.addxiaoyi.starx.example.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionRegistration;
import io.github.addxiaoyi.starx.api.extension.StarxService;
import io.github.addxiaoyi.starx.api.extension.StarxServiceProvider;
import io.github.addxiaoyi.starx.example.ExampleStarxExtension;
import org.slf4j.Logger;

/** Velocity entrypoint for the public API example. */
public final class StarxExampleVelocityPlugin {
  private final ProxyServer proxy;
  private final Logger logger;
  private StarxExtensionRegistration extensionRegistration;

  @Inject
  public StarxExampleVelocityPlugin(ProxyServer proxy, Logger logger) {
    this.proxy = proxy;
    this.logger = logger;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    PluginContainer starx = this.proxy.getPluginManager()
        .getPlugin("starx")
        .orElseThrow(() -> new IllegalStateException("StarX is not installed"));
    StarxService service = starx.getInstance()
        .filter(StarxServiceProvider.class::isInstance)
        .map(StarxServiceProvider.class::cast)
        .map(StarxServiceProvider::starxService)
        .orElseThrow(() -> new IllegalStateException("StarX service is not ready"));
    if (service.platform() != PlatformKind.VELOCITY) {
      throw new IllegalStateException("Expected Velocity StarX service, got " + service.platform());
    }
    this.extensionRegistration = service.registerExtension(
        ExampleStarxExtension.descriptor(this.implementationVersion()),
        new ExampleStarxExtension());
    this.logger.info(
        "STARX_EXTENSION_EXAMPLE_SERVICE=READY platform={} api={} extensions={}",
        service.platform(), service.apiVersion(), service.extensions().size());
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    StarxExtensionRegistration current = this.extensionRegistration;
    this.extensionRegistration = null;
    if (current != null) current.close();
    this.logger.info("STARX_EXTENSION_EXAMPLE_SERVICE=STOPPED platform=VELOCITY");
  }

  private String implementationVersion() {
    return this.proxy.getPluginManager().fromInstance(this)
        .flatMap(container -> container.getDescription().getVersion())
        .orElse("unknown");
  }
}
