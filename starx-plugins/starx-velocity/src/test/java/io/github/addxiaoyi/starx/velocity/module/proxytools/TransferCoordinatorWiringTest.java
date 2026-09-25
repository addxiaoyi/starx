package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertSame;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import io.github.addxiaoyi.starx.velocity.module.proxytools.queue.QueueService;
import io.github.addxiaoyi.starx.velocity.module.proxytools.smart.SmartQueueService;
import io.github.addxiaoyi.starx.velocity.routing.BackendRoutingService;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransferCoordinatorWiringTest {
  @TempDir Path directory;

  @Test
  void everyEntryPointUsesThePluginOwnedCoordinator() throws Exception {
    var plugin = new StarxVelocityPlugin(null, Logger.getAnonymousLogger(), this.directory);
    var routing = new BackendRoutingService(new BackendNodeRegistry());
    var modules = List.of(
        new HubCommandModule(plugin, HubCommandModule.Config.defaultConfig()),
        new EnhancedProxyModule(plugin, EnhancedProxyModule.Config.simpleDefault()),
        new QueueModule(plugin, QueueModule.Config.defaultConfig(), new QueueService(), routing),
        new SmartQueueModule(plugin, SmartQueueModule.Config.defaultConfig(), new SmartQueueService(), routing));
    for (var module : modules) {
      var field = module.getClass().getDeclaredField("transfers");
      field.setAccessible(true);
      assertSame(plugin.transferCoordinator(), field.get(module), module.name());
    }
    plugin.transferCoordinator().close();
  }
}
