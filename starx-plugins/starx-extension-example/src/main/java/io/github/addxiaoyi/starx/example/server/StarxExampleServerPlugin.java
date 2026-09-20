package io.github.addxiaoyi.starx.example.server;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionRegistration;
import io.github.addxiaoyi.starx.api.extension.StarxService;
import io.github.addxiaoyi.starx.example.ExampleStarxExtension;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper/Folia entrypoint for the public API example. */
public final class StarxExampleServerPlugin extends JavaPlugin {
  private StarxExtensionRegistration extensionRegistration;

  @Override
  public void onEnable() {
    RegisteredServiceProvider<StarxService> provider =
        this.getServer().getServicesManager().getRegistration(StarxService.class);
    if (provider == null) {
      throw new IllegalStateException("StarX service is not registered");
    }
    StarxService service = provider.getProvider();
    if (service.platform() != PlatformKind.PAPER && service.platform() != PlatformKind.FOLIA) {
      throw new IllegalStateException("Expected backend StarX service, got " + service.platform());
    }
    this.extensionRegistration = service.registerExtension(
        ExampleStarxExtension.descriptor(this.getPluginMeta().getVersion()),
        new ExampleStarxExtension());
    this.getLogger().info(
        "STARX_EXTENSION_EXAMPLE_SERVICE=READY platform=" + service.platform()
            + " api=" + service.apiVersion()
            + " extensions=" + service.extensions().size());
  }

  @Override
  public void onDisable() {
    StarxExtensionRegistration current = this.extensionRegistration;
    this.extensionRegistration = null;
    if (current != null) current.close();
    this.getLogger().info("STARX_EXTENSION_EXAMPLE_SERVICE=STOPPED");
  }
}
