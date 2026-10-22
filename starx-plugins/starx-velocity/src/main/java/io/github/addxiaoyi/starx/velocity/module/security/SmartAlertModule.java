package io.github.addxiaoyi.starx.velocity.module.security;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;

public final class SmartAlertModule implements VelocityModule {
  private static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(5);
  private static final int WARNING_THRESHOLD = 3;
  private static final int CRITICAL_THRESHOLD = 10;
  private static final long CLEAN_INTERVAL_SEC = 60;
  private static final int MAX_BUCKETS = 4_096;
  private static final int MAX_IP_LENGTH = 128;
  private static final String GENERATED_BY_KEY = "starx_generated_by";
  private static final String GENERATED_BY = "smart-alert";
  private static final String[] SUBSCRIBED_EVENTS = {
      "security:alert",
      "security:bot:detected",
      "security:crash:attempt",
      "security:risk:high",
      "security:risk:verify:required",
      "security:rate:limit:exceeded",
      "security:packet:suspicious",
      "security:anticheat:detection"};

  private final StarxVelocityPlugin plugin;
  private final EventBus eventBus;
  private final AlertBucketRegistry buckets = new AlertBucketRegistry(MAX_BUCKETS, WINDOW_MS);
  private final Consumer<StarxEvent> securitySubscriber = this::onSecurityEvent;
  private ScheduledExecutorService cleaner;

  public SmartAlertModule(StarxVelocityPlugin plugin, EventBus eventBus) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
  }

  @Override
  public String name() {
    return "starx.security.smart-alert";
  }

  @Override
  public void onEnable() {
    for (String eventType : SUBSCRIBED_EVENTS) {
      this.eventBus.subscribe(eventType, this.securitySubscriber);
    }
    this.cleaner = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "starx-smart-alert-cleaner");
      thread.setDaemon(true);
      return thread;
    });
    this.cleaner.scheduleAtFixedRate(
        this::cleanExpired,
        CLEAN_INTERVAL_SEC,
        CLEAN_INTERVAL_SEC,
        TimeUnit.SECONDS);
    this.plugin.logger().info(
        "SmartAlert: monitoring " + SUBSCRIBED_EVENTS.length + " event types");
  }

  @Override
  public void onDisable() {
    for (String eventType : SUBSCRIBED_EVENTS) {
      this.eventBus.unsubscribe(eventType, this.securitySubscriber);
    }
    if (this.cleaner != null) {
      this.cleaner.shutdownNow();
      this.cleaner = null;
    }
    this.buckets.clear();
  }

  private void onSecurityEvent(StarxEvent event) {
    if (GENERATED_BY.equals(event.payload().get(GENERATED_BY_KEY))) {
      return;
    }
    try {
      Object rawIp = event.payload().get("ip");
      String ip = rawIp instanceof String value
          && !value.isBlank()
          && value.length() <= MAX_IP_LENGTH
          ? value
          : "unknown";
      AlertBucketRegistry.Decision decision = this.buckets.record(event.type(), ip);
      if (decision.critical()) {
        this.plugin.logger().log(
            Level.SEVERE,
            "CRITICAL: {0} from {1} ({2} times in {3}m)",
            new Object[] {event.type(), ip, decision.count(), WINDOW_MS / 60000});
        this.eventBus.publish("security:alert", Map.of(
            "ip", ip,
            "type", event.type(),
            "severity", "CRITICAL",
            "count", String.valueOf(decision.count()),
            GENERATED_BY_KEY, GENERATED_BY));
      } else if (decision.warning()) {
        this.plugin.logger().log(
            Level.WARNING,
            "WARNING: {0} from {1} ({2} times)",
            new Object[] {event.type(), ip, decision.count()});
      }
    } catch (Exception error) {
      this.plugin.logger().log(Level.FINE, "SmartAlert: processing failed", error);
    }
  }

  private void cleanExpired() {
    this.buckets.cleanExpired();
  }

  int getBucketCount() {
    return this.buckets.size();
  }

  void clearBuckets() {
    this.buckets.clear();
  }

  static final class AlertBucketRegistry {
    private final int capacity;
    private final long windowMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<String, AlertBucket> buckets =
        new LinkedHashMap<>(16, 0.75f, true);

    AlertBucketRegistry(int capacity, long windowMillis) {
      this(capacity, windowMillis, System::currentTimeMillis);
    }

    AlertBucketRegistry(int capacity, long windowMillis, LongSupplier clock) {
      if (capacity < 1 || windowMillis < 1) {
        throw new IllegalArgumentException("capacity and windowMillis must be positive");
      }
      this.capacity = capacity;
      this.windowMillis = windowMillis;
      this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized Decision record(String eventType, String ip) {
      String key = eventType + ":" + ip;
      long now = this.clock.getAsLong();
      AlertBucket bucket = this.buckets.get(key);
      if (bucket == null || isExpired(bucket, now)) {
        bucket = new AlertBucket(now);
        this.buckets.put(key, bucket);
      } else {
        bucket.count++;
      }
      while (this.buckets.size() > this.capacity) {
        this.buckets.remove(this.buckets.keySet().iterator().next());
      }

      boolean critical = bucket.count >= CRITICAL_THRESHOLD && !bucket.criticalEmitted;
      boolean warning = bucket.count >= WARNING_THRESHOLD && !bucket.warningEmitted;
      if (critical) {
        bucket.criticalEmitted = true;
      }
      if (warning) {
        bucket.warningEmitted = true;
      }
      return new Decision(bucket.count, warning, critical);
    }

    synchronized void cleanExpired() {
      long now = this.clock.getAsLong();
      this.buckets.entrySet().removeIf(
          entry -> isExpired(entry.getValue(), now));
    }

    private boolean isExpired(AlertBucket bucket, long now) {
      return now < bucket.startedAt || now - bucket.startedAt >= this.windowMillis;
    }

    synchronized int size() {
      return this.buckets.size();
    }

    synchronized void clear() {
      this.buckets.clear();
    }

    record Decision(int count, boolean warning, boolean critical) {
    }

    private static final class AlertBucket {
      private final long startedAt;
      private int count = 1;
      private boolean warningEmitted;
      private boolean criticalEmitted;

      private AlertBucket(long startedAt) {
        this.startedAt = startedAt;
      }
    }
  }
}
