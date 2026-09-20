package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.config.UworldConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UworldModuleLifecycleRetryTest {

  @Test
  void disableRetainsEachFailedResourceAndRetriesIt(@TempDir Path dataDirectory) throws Exception {
    AtomicInteger unregisterAttempts = new AtomicInteger();
    EventManager events = proxy(EventManager.class, (instance, method, args) -> {
      if (method.getName().equals("unregisterListener")) {
        if (unregisterAttempts.incrementAndGet() == 1) {
          throw new IllegalStateException("listener unregister rejected");
        }
      }
      return defaultValue(method.getReturnType());
    });
    ProxyServer proxy = proxy(ProxyServer.class, (instance, method, args) ->
        method.getName().equals("getEventManager")
            ? events
            : defaultValue(method.getReturnType()));
    StarxVelocityPlugin plugin = new StarxVelocityPlugin(
        proxy,
        Logger.getLogger(UworldModuleLifecycleRetryTest.class.getName()),
        dataDirectory);
    UworldModule module = new UworldModule(plugin, UworldConfig.defaults());
    AtomicInteger coreCloseAttempts = new AtomicInteger();
    UworldModule.CoreResource factory = () -> {
      if (coreCloseAttempts.incrementAndGet() == 1) {
        throw new IllegalStateException("core close rejected");
      }
    };
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        new UworldRuntimeTestSupport.FactoryProbe().factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> action.run(),
        factory::close);
    Object listener = newListener(module);
    set(module, "factory", factory);
    set(module, "runtime", runtime);
    set(module, "listener", listener);

    assertThrows(IllegalStateException.class, module::onDisable);

    assertSame(listener, get(module, "listener"));
    assertSame(runtime, get(module, "runtime"));
    assertSame(factory, get(module, "factory"));
    assertEquals(1, unregisterAttempts.get());
    assertEquals(1, coreCloseAttempts.get());

    module.onDisable();
    module.onDisable();

    assertNull(get(module, "listener"));
    assertNull(get(module, "runtime"));
    assertNull(get(module, "factory"));
    assertEquals(2, unregisterAttempts.get());
    assertEquals(2, coreCloseAttempts.get());
  }

  private static Object newListener(UworldModule module) throws Exception {
    Class<?> type = java.util.Arrays.stream(UworldModule.class.getDeclaredClasses())
        .filter(candidate -> candidate.getSimpleName().equals("EventListener"))
        .findFirst()
        .orElseThrow();
    Constructor<?> constructor = type.getDeclaredConstructor(UworldModule.class);
    constructor.setAccessible(true);
    return constructor.newInstance(module);
  }

  private static Object get(UworldModule module, String name) throws Exception {
    Field field = UworldModule.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(module);
  }

  private static void set(UworldModule module, String name, Object value) throws Exception {
    Field field = UworldModule.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(module, value);
  }

  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }
}
