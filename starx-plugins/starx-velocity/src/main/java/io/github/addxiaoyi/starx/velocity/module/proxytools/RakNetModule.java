package io.github.addxiaoyi.starx.velocity.module.proxytools;

import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RakNetModule implements VelocityModule {

  private final StarxVelocityPlugin plugin;
  private final Config config;
  private final AtomicBoolean initialized = new AtomicBoolean();
  private volatile RakNetProviderResolver.Provider provider = RakNetProviderResolver.Provider.NONE;

  public RakNetModule(StarxVelocityPlugin plugin, Config config) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.config = Objects.requireNonNull(config, "config");
  }

  @Override
  public String name() {
    return "starx.proxytools.raknet";
  }

  @Override
  public void onEnable() {
    if (!this.config.enabled()) {
      return;
    }
    Set<String> pluginIds = new TreeSet<>();
    this.plugin.proxy().getPluginManager().getPlugins().forEach(container ->
        pluginIds.add(container.getDescription().getId()));
    this.provider = RakNetProviderResolver.resolve(pluginIds);
    if (this.provider == RakNetProviderResolver.Provider.NONE) {
      this.initialized.set(false);
      String message = "RakNet compatibility enabled but no Geyser or Raknetify provider is installed";
      if (this.config.requireProvider()) {
        throw new IllegalStateException(message);
      }
      this.plugin.logger().warning(message + "; StarX will not pretend to own a UDP listener");
      return;
    }

    this.initialized.set(true);
    this.plugin.logger().info(
        "RakNet compatibility delegated to " + this.provider + " on configured port "
            + this.config.port());
  }

  @Override
  public void onDisable() {
    this.initialized.set(false);
    this.provider = RakNetProviderResolver.Provider.NONE;
  }

  public boolean isInitialized() {
    return this.initialized.get();
  }

  public int port() {
    return this.config.port();
  }

  RakNetProviderResolver.Provider provider() {
    return this.provider;
  }

  public static final class Config {
    private final boolean enabled;
    private final int port;
    private final boolean debug;
    private final boolean requireProvider;

    private Config(boolean enabled, int port, boolean debug, boolean requireProvider) {
      if (port < 1 || port > 65_535) {
        throw new IllegalArgumentException("RakNet port must be between 1 and 65535");
      }
      this.enabled = enabled;
      this.port = port;
      this.debug = debug;
      this.requireProvider = requireProvider;
    }

    public static Config from(StarxConfig.ModuleConfig module) {
      if (module == null) {
        return defaultConfig();
      }
      return new Config(
          module.enabled(),
          module.intOption("port", 19_132),
          module.booleanOption("debug", false),
          module.booleanOption("require-provider", false));
    }

    public static Config defaultConfig() {
      return new Config(false, 19_132, false, false);
    }

    public boolean enabled() {
      return this.enabled;
    }

    public int port() {
      return this.port;
    }

    public boolean debug() {
      return this.debug;
    }

    public boolean requireProvider() {
      return this.requireProvider;
    }
  }
}
