package io.github.addxiaoyi.starx.velocity.module.tab;

import static org.junit.jupiter.api.Assertions.*;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class CrossServerTabLifecycleTest {
  @TempDir Path directory;

  @Test
  void periodicRefreshPrunesSnapshotsLeftByADisconnectRace() throws Exception {
    var runtime = new RuntimeStub();
    var module = module(runtime);
    module.onEnable();
    var entries = CrossServerTabModule.class.getDeclaredField("sentEntries");
    entries.setAccessible(true);
    @SuppressWarnings("unchecked")
    var snapshots = (java.util.Map<java.util.UUID, java.util.Map<?, ?>>) entries.get(module);
    snapshots.put(java.util.UUID.randomUUID(), java.util.Map.of());
    runtime.tasks.getFirst().action.run();
    assertTrue(snapshots.isEmpty());
    module.onDisable();
  }

  @Test
  void cancelsBothTasksAndRejectsCallbacksFromEarlierEnableCycle() {
    var runtime = new RuntimeStub();
    var module = module(runtime);
    module.onEnable();
    assertEquals(2, runtime.tasks.size());
    List<CapturedTask> previous = List.copyOf(runtime.tasks);
    previous.getFirst().action.run();
    assertTrue(runtime.rosterReads.get() > 0);
    module.onDisable();
    assertTrue(previous.stream().allMatch(task -> task.cancelled.get()));
    int reads = runtime.rosterReads.get();
    previous.forEach(task -> task.action.run());
    assertEquals(reads, runtime.rosterReads.get());
    module.onEnable();
    previous.forEach(task -> task.action.run());
    assertEquals(reads, runtime.rosterReads.get());
    runtime.tasks.get(2).action.run();
    assertTrue(runtime.rosterReads.get() > reads);
    module.onDisable();
  }

  @Test
  void disableWaitsForCurrentReconciliationBeforeCleaningManagedEntries() throws Exception {
    var runtime = new RuntimeStub();
    var module = module(runtime);
    module.onEnable();
    runtime.blockNextRead.set(true);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var refresh = workers.submit(runtime.tasks.getFirst().action);
      try {
        assertTrue(runtime.readStarted.await(5, TimeUnit.SECONDS));
        var schedule = CrossServerTabModule.class.getDeclaredMethod("scheduleReconcile", java.time.Duration.class);
        schedule.setAccessible(true);
        var playerEvent = workers.submit(() -> {
          schedule.invoke(module, java.time.Duration.ZERO);
          return null;
        });
        playerEvent.get(2, TimeUnit.SECONDS);
        var disable = workers.submit(module::onDisable);
        runtime.resumeRead.countDown();
        refresh.get(5, TimeUnit.SECONDS);
        disable.get(5, TimeUnit.SECONDS);
        int reads = runtime.rosterReads.get();
        runtime.tasks.forEach(task -> task.action.run());
        assertEquals(reads, runtime.rosterReads.get());
        assertTrue(runtime.tasks.stream().allMatch(task -> task.cancelled.get()));
      } finally {
        runtime.resumeRead.countDown();
      }
    }
  }

  private CrossServerTabModule module(RuntimeStub runtime) {
    return new CrossServerTabModule(new StarxVelocityPlugin(
        runtime.proxy(), Logger.getAnonymousLogger(), this.directory));
  }

  private static final class RuntimeStub {
    private final List<CapturedTask> tasks = new ArrayList<>();
    private final AtomicInteger rosterReads = new AtomicInteger();
    private final AtomicBoolean blockNextRead = new AtomicBoolean();
    private final CountDownLatch readStarted = new CountDownLatch(1);
    private final CountDownLatch resumeRead = new CountDownLatch(1);

    ProxyServer proxy() {
      EventManager events = (EventManager) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {EventManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("register") || method.getName().equals("unregisterListener")) return null;
            throw new UnsupportedOperationException(method.getName());
          });
      Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {Scheduler.class}, (proxy, method, args) -> {
            if (method.getName().equals("buildTask")) return builder((Runnable) args[1]);
            throw new UnsupportedOperationException(method.getName());
          });
      return (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {ProxyServer.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getScheduler" -> scheduler;
            case "getEventManager" -> events;
            case "getAllPlayers" -> {
              this.rosterReads.incrementAndGet();
              if (this.blockNextRead.compareAndSet(true, false)) {
                this.readStarted.countDown();
                if (!this.resumeRead.await(5, TimeUnit.SECONDS)) throw new AssertionError("refresh not released");
              }
              yield List.of();
            }
            default -> throw new UnsupportedOperationException(method.getName());
          });
    }

    private Scheduler.TaskBuilder builder(Runnable action) {
      CapturedTask captured = new CapturedTask(action, new AtomicBoolean());
      ScheduledTask task = (ScheduledTask) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {ScheduledTask.class}, (proxy, method, args) -> {
            if (method.getName().equals("cancel")) { captured.cancelled.set(true); return null; }
            throw new UnsupportedOperationException(method.getName());
          });
      return (Scheduler.TaskBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {Scheduler.TaskBuilder.class}, (proxy, method, args) -> switch (method.getName()) {
            case "repeat", "delay" -> proxy;
            case "schedule" -> { this.tasks.add(captured); yield task; }
            default -> throw new UnsupportedOperationException(method.getName());
          });
    }
  }

  private record CapturedTask(Runnable action, AtomicBoolean cancelled) { }
}
