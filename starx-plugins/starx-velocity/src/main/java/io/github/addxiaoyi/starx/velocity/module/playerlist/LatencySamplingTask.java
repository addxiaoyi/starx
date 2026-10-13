package io.github.addxiaoyi.starx.velocity.module.playerlist;

import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import java.time.Duration;
import java.util.Objects;

final class LatencySamplingTask {
  private static final Duration SAMPLE_PERIOD = Duration.ofMillis(250);

  private LatencySamplingTask() {
  }

  static ScheduledTask schedule(
      Scheduler scheduler,
      Object plugin,
      boolean needed,
      Runnable sample
  ) {
    Objects.requireNonNull(scheduler, "scheduler");
    Objects.requireNonNull(plugin, "plugin");
    Objects.requireNonNull(sample, "sample");
    if (!needed) return null;
    return scheduler.buildTask(plugin, sample).repeat(SAMPLE_PERIOD).schedule();
  }
}
