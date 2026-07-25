package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.addxiaoyi.starx.uworld.UworldEnterResult;
import io.github.addxiaoyi.starx.uworld.UworldFlowOptions;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.module.ModuleManager;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class UworldShutdownLifecycleTest {

  @Test
  void runtimeShutdownWinsBeforeAConsumerClosesItsWorld() {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> action.run(),
        () -> { });
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), editor -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("module-shutdown");
    player.routeDisconnectsTo(runtime);
    UworldFlowSession session = assertInstanceOf(
        UworldEnterResult.Accepted.class,
        world.enter(
            player.player(),
            UworldFlowOptions.defaults(),
            new UworldRuntimeTestSupport.HandlerProbe())).session();
    ModuleManager manager = new ModuleManager(config());
    manager.register(new RuntimeOwner(runtime));
    manager.register(new WorldConsumer(world));
    manager.enableAll();

    manager.disableAll();

    assertEquals(
        UworldOutcomeType.RUNTIME_STOPPING,
        session.completion().toCompletableFuture().join().type());
  }

  private static StarxConfig config() {
    Map<String, StarxConfig.ModuleConfig> modules = Map.of(
        "test.runtime", new StarxConfig.ModuleConfig(true),
        "test.consumer", new StarxConfig.ModuleConfig(true));
    return new StarxConfig(
        "",
        new StarxConfig.HttpConfig("127.0.0.1", 8788),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        modules);
  }

  private record RuntimeOwner(EmbeddedUworldRuntime runtime) implements VelocityModule {
    @Override
    public String name() {
      return "test.runtime";
    }

    @Override
    public void onShutdownStart() {
      this.runtime.closeAsync(Component.text("runtime stopping"))
          .toCompletableFuture()
          .join();
    }
  }

  private record WorldConsumer(UworldHandle world) implements VelocityModule {
    @Override
    public String name() {
      return "test.consumer";
    }

    @Override
    public void onDisable() {
      this.world.closeAsync(Component.text("consumer stopping"))
          .toCompletableFuture()
          .join();
    }
  }
}
