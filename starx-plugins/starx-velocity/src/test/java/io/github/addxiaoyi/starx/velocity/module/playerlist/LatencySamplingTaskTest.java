package io.github.addxiaoyi.starx.velocity.module.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LatencySamplingTaskTest {
  @Test
  void enablesLatencySamplingOnlyForPingVariables() {
    assertFalse(PlayerListModule.needsLatencyData(Set.of("starx_player", "starx_server")));
    assertTrue(PlayerListModule.needsLatencyData(Set.of("starx_ping")));
    assertTrue(PlayerListModule.needsLatencyData(Set.of("starx_ping_raw")));
  }

  @Test
  void doesNotCreateTaskWhenPingVariablesAreUnused() {
    AtomicInteger schedules = new AtomicInteger();
    Scheduler scheduler = scheduler(schedules, new AtomicReference<>());

    ScheduledTask task = LatencySamplingTask.schedule(
        scheduler, new Object(), false, () -> { });

    assertNull(task);
    assertEquals(0, schedules.get());
  }

  @Test
  void creates250MillisecondTaskOnlyWhenPingVariablesAreNeeded() {
    AtomicInteger schedules = new AtomicInteger();
    AtomicReference<Duration> period = new AtomicReference<>();
    AtomicReference<ScheduledTask> captured = new AtomicReference<>();
    AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
    Scheduler scheduler = scheduler(schedules, period, captured, cancelled);
    Runnable sample = () -> { };

    ScheduledTask task = LatencySamplingTask.schedule(
        scheduler, new Object(), true, sample);

    assertEquals(1, schedules.get());
    assertEquals(Duration.ofMillis(250), period.get());
    assertSame(task, captured.get());
    task.cancel();
    assertEquals(Boolean.TRUE, cancelled.get());
  }

  private static Scheduler scheduler(AtomicInteger schedules, AtomicReference<Duration> period) {
    return scheduler(schedules, period, new AtomicReference<>(), new AtomicReference<>(false));
  }

  private static Scheduler scheduler(
      AtomicInteger schedules,
      AtomicReference<Duration> period,
      AtomicReference<ScheduledTask> captured,
      AtomicReference<Boolean> cancelled
  ) {
    return (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(),
        new Class<?>[] {Scheduler.class}, (proxy, method, args) -> {
          if (!method.getName().equals("buildTask")) {
            throw new UnsupportedOperationException(method.getName());
          }
          return Proxy.newProxyInstance(Scheduler.TaskBuilder.class.getClassLoader(),
              new Class<?>[] {Scheduler.TaskBuilder.class}, (builder, buildMethod, buildArgs) -> {
                if (buildMethod.getName().equals("repeat")) {
                  period.set((Duration) buildArgs[0]);
                  return builder;
                }
                if (buildMethod.getName().equals("schedule")) {
                  schedules.incrementAndGet();
                  ScheduledTask task = (ScheduledTask) Proxy.newProxyInstance(
                      ScheduledTask.class.getClassLoader(), new Class<?>[] {ScheduledTask.class},
                      (taskProxy, taskMethod, taskArgs) -> {
                        if (taskMethod.getName().equals("cancel")) {
                          cancelled.set(true);
                          return null;
                        }
                        if (taskMethod.getName().equals("status")) return null;
                        throw new UnsupportedOperationException(taskMethod.getName());
                      });
                  captured.set(task);
                  return task;
                }
                throw new UnsupportedOperationException(buildMethod.getName());
              });
        });
  }
}
