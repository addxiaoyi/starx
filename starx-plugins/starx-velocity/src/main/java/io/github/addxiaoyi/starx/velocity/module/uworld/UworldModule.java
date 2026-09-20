package io.github.addxiaoyi.starx.velocity.module.uworld;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.uworld.StarxUworldFactory;
import io.github.addxiaoyi.starx.uworld.UworldCreationException;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldRuntime;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import io.github.addxiaoyi.starx.uworld.UworldWorldGenerator;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.config.UworldConfig;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import org.slf4j.LoggerFactory;

public final class UworldModule implements VelocityModule, UworldRuntime {

  @FunctionalInterface
  interface CoreResource {
    void close();
  }

  public static final String MODULE_ID = "starx.uworld";

  private static final Component STOPPING = Component.text(
      "Uworld runtime is stopping. Please reconnect.");

  private final StarxVelocityPlugin plugin;
  private final UworldConfig config;

  private volatile CoreResource factory;
  private volatile EmbeddedUworldRuntime runtime;
  private EventListener listener;

  public UworldModule(StarxVelocityPlugin plugin, UworldConfig config) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.config = Objects.requireNonNull(config, "config");
  }

  @Override
  public String name() {
    return MODULE_ID;
  }

  @Override
  public void onEnable() {
    if (!this.config.enabled()) {
      this.plugin.logger().warning("Uworld runtime is disabled; managed virtual worlds are unavailable");
      return;
    }

    try {
      Path coreDirectory = UworldCorePathResolver.resolve(
          this.plugin.dataDirectory(),
          this.plugin.logger()::warning);
      Files.createDirectories(coreDirectory);
      StarxUworldFactory core = new StarxUworldFactory(
          LoggerFactory.getLogger(StarxUworldFactory.class),
          this.plugin.proxy(),
          coreDirectory);
      CoreResource coreResource = core::close;
      this.factory = coreResource;
      core.initialize(this.plugin);
      this.plugin.logger().info("Uworld core initialized");

      EmbeddedUworldRuntime managed = new EmbeddedUworldRuntime(
          core,
          (delay, action) -> {
            var task = this.plugin.proxy().getScheduler()
                .buildTask(this.plugin, action)
                .delay(delay)
                .schedule();
            return task::cancel;
          },
          core::execute,
          coreResource::close);
      this.runtime = managed;
      this.listener = new EventListener();
      this.plugin.proxy().getEventManager().register(this.plugin, this.listener);
      this.plugin.logger().info("Uworld runtime ready");
    } catch (IOException | RuntimeException error) {
      this.rollbackEnable(error);
      throw new IllegalStateException("Unable to initialize Uworld; authentication is fail-closed", error);
    }
  }

  @Override
  public void onShutdownStart() {
    EmbeddedUworldRuntime current = this.runtime;
    if (current == null) {
      return;
    }
    try {
      current.closeAsync(STOPPING).toCompletableFuture().join();
    } catch (RuntimeException error) {
      Throwable cause = error instanceof CompletionException && error.getCause() != null
          ? error.getCause()
          : error;
      throw new IllegalStateException("Unable to start Uworld runtime shutdown", cause);
    }
  }

  @Override
  public void onDisable() {
    IllegalStateException failure = null;
    EventListener currentListener = this.listener;
    if (currentListener != null) {
      try {
        this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        if (this.listener == currentListener) {
          this.listener = null;
        }
      } catch (RuntimeException error) {
        failure = stopFailure(failure, "event listener", error);
      }
    }

    EmbeddedUworldRuntime current = this.runtime;
    CoreResource currentFactory = this.factory;
    if (current != null) {
      try {
        current.closeAsync(STOPPING).toCompletableFuture().join();
        if (this.runtime == current) {
          this.runtime = null;
        }
        if (this.factory == currentFactory) {
          this.factory = null;
        }
      } catch (RuntimeException error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause()
            : error;
        failure = stopFailure(failure, "runtime", cause);
      }
    } else {
      try {
        this.closeFailedCore();
      } catch (RuntimeException error) {
        failure = stopFailure(failure, "core", error);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  @Override
  public boolean isReady() {
    EmbeddedUworldRuntime current = this.runtime;
    return current != null && current.isReady();
  }

  @Override
  public UworldHandle createWorld(
      String owner,
      UworldSpec spec,
      UworldWorldGenerator generator
  ) {
    EmbeddedUworldRuntime current = this.runtime;
    if (current == null || !current.isReady()) {
      String worldName = spec == null ? "unknown" : spec.name();
      throw new UworldCreationException(owner, worldName, "runtime is not ready");
    }
    return current.createWorld(owner, spec, generator);
  }

  @Override
  public Optional<UworldFlowSession> session(Player player) {
    EmbeddedUworldRuntime current = this.runtime;
    return current == null ? Optional.empty() : current.session(player);
  }

  @Override
  public int worldCount() {
    EmbeddedUworldRuntime current = this.runtime;
    return current == null ? 0 : current.worldCount();
  }

  @Override
  public int sessionCount() {
    EmbeddedUworldRuntime current = this.runtime;
    return current == null ? 0 : current.sessionCount();
  }

  private void closeFailedCore() {
    CoreResource current = this.factory;
    if (current != null) {
      current.close();
      if (this.factory == current) {
        this.factory = null;
      }
    }
  }

  private void rollbackEnable(Exception failure) {
    EventListener currentListener = this.listener;
    if (currentListener != null) {
      try {
        this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        if (this.listener == currentListener) {
          this.listener = null;
        }
      } catch (RuntimeException error) {
        failure.addSuppressed(error);
      }
    }

    EmbeddedUworldRuntime current = this.runtime;
    CoreResource currentFactory = this.factory;
    if (current != null) {
      try {
        current.closeAsync(STOPPING).toCompletableFuture().join();
        if (this.runtime == current) {
          this.runtime = null;
        }
        if (this.factory == currentFactory) {
          this.factory = null;
        }
      } catch (RuntimeException error) {
        failure.addSuppressed(error);
      }
      return;
    }
    try {
      this.closeFailedCore();
    } catch (RuntimeException error) {
      failure.addSuppressed(error);
    }
  }

  private static IllegalStateException stopFailure(
      IllegalStateException failure,
      String resource,
      Throwable error
  ) {
    IllegalStateException aggregate = failure == null
        ? new IllegalStateException("One or more Uworld resources failed to stop")
        : failure;
    aggregate.addSuppressed(new IllegalStateException(
        "Unable to stop Uworld " + resource, error));
    return aggregate;
  }

  private final class EventListener {

    @Subscribe(order = PostOrder.LAST)
    public void onPreConnect(ServerPreConnectEvent event) {
      EmbeddedUworldRuntime current = UworldModule.this.runtime;
      if (current != null) {
        current.onPreConnect(event);
      }
    }

    @Subscribe
    public void onConnected(ServerConnectedEvent event) {
      EmbeddedUworldRuntime current = UworldModule.this.runtime;
      if (current != null) {
        current.onConnected(event.getPlayer(), event.getServer());
      }
    }

    @Subscribe
    public void onKick(KickedFromServerEvent event) {
      EmbeddedUworldRuntime current = UworldModule.this.runtime;
      if (current != null && current.session(event.getPlayer()).isPresent()) {
        Component reason = event.getServerKickReason().orElse(
            Component.text("子服拒绝了 Uworld 转服"));
        current.onKick(event.getPlayer(), reason);
      }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
      EmbeddedUworldRuntime current = UworldModule.this.runtime;
      if (current != null) {
        current.onDisconnect(event.getPlayer());
      }
    }
  }
}
