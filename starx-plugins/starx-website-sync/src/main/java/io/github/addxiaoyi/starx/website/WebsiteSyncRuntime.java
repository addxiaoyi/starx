package io.github.addxiaoyi.starx.website;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class WebsiteSyncRuntime implements AutoCloseable {
  public enum State {
    DISABLED,
    WAITING_FOR_CREDENTIALS,
    ENROLLING,
    ACTIVE,
    BACKOFF,
    AUTH_FAILED,
    STOPPED
  }

  public record Snapshot(
      State state,
      String lastHeartbeatAt,
      String lastTextureSyncAt,
      String lastErrorCode,
      int queuedTasks,
      int rejectedTextures
  ) {
  }

  public record HealthChange(
      String component,
      String previousState,
      String state,
      String errorCode,
      int queueSize,
      long rejectedTasks,
      Instant occurredAt
  ) {
  }

  private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
  private static final int WORKER_CORE_THREADS = 1;
  private static final int WORKER_MAX_THREADS = 2;
  private static final int WORKER_QUEUE_CAPACITY = 8;
  private final WebsiteSyncConfig config;
  private final WebsiteSyncClient client;
  private final WebsiteSyncCredentialStore credentials;
  private final Supplier<NodeSnapshot> snapshotSupplier;
  private final TextureSource textureSource;
  private final List<String> capabilities;
  private final Consumer<String> logger;
  private final Clock clock;
  private final ScheduledThreadPoolExecutor scheduler;
  private final ThreadPoolExecutor workers;
  private final AtomicLong rejectedWorkerTasks = new AtomicLong();
  private final AtomicReference<SecretValue> nodeToken;
  private final AtomicReference<State> state;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean heartbeatInFlight = new AtomicBoolean();
  private final AtomicBoolean texturesInFlight = new AtomicBoolean();
  private final AtomicBoolean workerQueueSaturated = new AtomicBoolean();
  private final Object heartbeatScheduleLock = new Object();
  private final Object textureScheduleLock = new Object();
  private final ExponentialBackoff heartbeatBackoff;
  private final ExponentialBackoff textureBackoff;
  private final Set<String> rejectedTextureHashes = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private volatile long nextHeartbeatMillis;
  private volatile long nextTextureMillis;
  private volatile Instant lastHeartbeat;
  private volatile Instant lastTextureSync;
  private volatile String lastErrorCode = "";
  private volatile NodeSnapshot latestSnapshot;
  private volatile Map<String, String> lastTextureManifest = Map.of();
  private volatile Consumer<HealthChange> healthChangeListener = ignored -> { };
  private ScheduledFuture<?> heartbeatFuture;
  private ScheduledFuture<?> textureFuture;

  public WebsiteSyncRuntime(
      WebsiteSyncConfig config,
      WebsiteSyncClient client,
      WebsiteSyncCredentialStore credentials,
      Supplier<NodeSnapshot> snapshotSupplier,
      TextureSource textureSource,
      Collection<String> capabilities,
      Consumer<String> logger
  ) {
    this(config, client, credentials, snapshotSupplier, textureSource, capabilities, logger,
        Clock.systemUTC());
  }

  WebsiteSyncRuntime(
      WebsiteSyncConfig config,
      WebsiteSyncClient client,
      WebsiteSyncCredentialStore credentials,
      Supplier<NodeSnapshot> snapshotSupplier,
      TextureSource textureSource,
      Collection<String> capabilities,
      Consumer<String> logger,
      Clock clock
  ) {
    this.config = Objects.requireNonNull(config, "config");
    this.client = Objects.requireNonNull(client, "client");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
    this.textureSource = textureSource == null ? TextureSource.empty() : textureSource;
    this.capabilities = NodeCapabilities.normalize(capabilities);
    this.logger = logger == null ? ignored -> { } : logger;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.nodeToken = new AtomicReference<>(config.nodeToken());
    this.state = new AtomicReference<>(config.enabled()
        ? State.WAITING_FOR_CREDENTIALS : State.DISABLED);
    this.heartbeatBackoff = new ExponentialBackoff(
        Duration.ofSeconds(Math.max(5, config.heartbeat().intervalSeconds())), MAX_BACKOFF);
    this.textureBackoff = new ExponentialBackoff(Duration.ofSeconds(15), MAX_BACKOFF);
    this.scheduler = new ScheduledThreadPoolExecutor(1, daemonFactory("starx-website-schedule"));
    this.scheduler.setKeepAliveTime(1, TimeUnit.SECONDS);
    this.scheduler.allowCoreThreadTimeOut(true);
    this.scheduler.setRemoveOnCancelPolicy(true);
    this.workers = new ThreadPoolExecutor(
        4,
        8,
        1,
        TimeUnit.SECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(WORKER_QUEUE_CAPACITY),
        daemonFactory("starx-website-http"),
        (task, executor) -> {
          this.rejectedWorkerTasks.incrementAndGet();
          throw new RejectedExecutionException("website sync worker queue is full");
        });
    this.workers.allowCoreThreadTimeOut(true);
  }

  public void start() {
    if (!this.config.enabled() || !this.started.compareAndSet(false, true)) {
      return;
    }
    long now = this.clock.millis();
    this.nextHeartbeatMillis = now;
    this.nextTextureMillis = now;
    scheduleHeartbeat(Duration.ZERO);
  }

  public void setHealthChangeListener(Consumer<HealthChange> listener) {
    this.healthChangeListener = Objects.requireNonNull(listener, "listener");
  }

  public void forceHeartbeat() {
    if (this.state.get() != State.AUTH_FAILED && this.state.get() != State.STOPPED) {
      this.nextHeartbeatMillis = 0;
      if (this.started.get()) scheduleHeartbeat(Duration.ZERO);
    }
  }

  public Snapshot snapshot() {
    return new Snapshot(
        this.state.get(),
        this.lastHeartbeat == null ? null : this.lastHeartbeat.toString(),
        this.lastTextureSync == null ? null : this.lastTextureSync.toString(),
        this.lastErrorCode.isBlank() ? null : this.lastErrorCode,
        this.workers.getQueue().size(),
        this.rejectedTextureHashes.size());
  }

  int workerThreadCount() {
    return this.workers.getPoolSize();
  }

  public Map<String, Object> runtimeMetrics() {
    return Map.of(
        "workerThreads", this.workers.getPoolSize(),
        "activeWorkers", this.workers.getActiveCount(),
        "queueSize", this.workers.getQueue().size(),
        "rejectedTasks", this.rejectedWorkerTasks.get(),
        "workerQueueSaturated", this.workerQueueSaturated.get(),
        "scheduledTasks", this.scheduler.getQueue().size());
  }

  int scheduledTaskCount() {
    return this.scheduler.getQueue().size();
  }

  private void heartbeatTick() {
    if (!mayRun(this.nextHeartbeatMillis)) {
      scheduleHeartbeat(nextHeartbeatDelay());
      return;
    }
    if (!this.heartbeatInFlight.compareAndSet(false, true)) {
      return;
    }
    boolean accepted = submit(() -> {
      try {
        publishHeartbeat();
      } finally {
        this.heartbeatInFlight.set(false);
        scheduleHeartbeat(nextHeartbeatDelay());
      }
    }, this.heartbeatInFlight);
    if (!accepted) {
      this.nextHeartbeatMillis = this.clock.millis() + 1_000L;
      scheduleHeartbeat(Duration.ofSeconds(1));
    }
  }

  private void scheduleHeartbeat(Duration delay) {
    if (!this.started.get() || this.state.get() == State.STOPPED
        || this.state.get() == State.AUTH_FAILED || this.nextHeartbeatMillis == Long.MAX_VALUE) {
      return;
    }
    try {
      synchronized (this.heartbeatScheduleLock) {
        if (this.heartbeatFuture != null) this.heartbeatFuture.cancel(false);
        this.heartbeatFuture = this.scheduler.schedule(
            this::runHeartbeatSchedule, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
      }
    } catch (RejectedExecutionException error) {
      if (this.state.get() != State.STOPPED) {
        this.logger.accept(
            "StarX website heartbeat scheduler rejected node=" + this.config.nodeId());
      }
    }
  }

  private void runHeartbeatSchedule() {
    synchronized (this.heartbeatScheduleLock) {
      this.heartbeatFuture = null;
    }
    try {
      heartbeatTick();
    } catch (RuntimeException error) {
      this.heartbeatInFlight.set(false);
      handleFailure("heartbeat_scheduler_failed", this.heartbeatBackoff.next(), true);
      this.logger.accept(
          "StarX website heartbeat scheduler recovered after runtime failure: node="
              + this.config.nodeId() + " error=" + error.getClass().getSimpleName());
      scheduleHeartbeat(nextHeartbeatDelay());
    }
  }

  private Duration nextHeartbeatDelay() {
    long due = this.nextHeartbeatMillis;
    if (due == Long.MAX_VALUE) return Duration.ZERO;
    return Duration.ofMillis(Math.max(0, due - this.clock.millis()));
  }

  private void publishHeartbeat() {
    SecretValue token = this.nodeToken.get();
    try {
      if (!token.isPresent()) {
        if (!this.config.bootstrapToken().isPresent()) {
          transitionTo(State.WAITING_FOR_CREDENTIALS);
          this.nextHeartbeatMillis = Long.MAX_VALUE;
          return;
        }
        transitionTo(State.ENROLLING);
        Enrollment enrollment = this.client.enroll(
            this.config.bootstrapToken(),
            this.config.nodeId(),
            this.config.platform(),
            this.capabilities);
        if (!this.config.nodeId().equals(enrollment.nodeId())) {
          throw new WebsiteSyncApiException(
              200, "node_mismatch", "enrollment returned a different node id");
        }
        this.credentials.persistEnrollment(enrollment.nodeToken());
        this.nodeToken.set(enrollment.nodeToken());
        this.logger.accept("StarX website node enrollment completed: node=" + this.config.nodeId());
        token = enrollment.nodeToken();
      }
      NodeSnapshot snapshot = Objects.requireNonNull(
          this.snapshotSupplier.get(), "snapshotSupplier returned null");
      this.latestSnapshot = snapshot;
      this.client.heartbeat(token, this.config.nodeId(), this.capabilities, snapshot);
      boolean recovered = this.state.get() == State.BACKOFF;
      transitionTo(State.ACTIVE);
      this.lastHeartbeat = this.clock.instant();
      this.lastErrorCode = "";
      this.heartbeatBackoff.reset();
      this.nextHeartbeatMillis = this.clock.millis() + this.config.heartbeat().interval().toMillis();
      if (this.config.textures().enabled()) scheduleTextureTick(nextTextureDelay());
      if (recovered) {
        this.logger.accept("StarX website synchronization recovered: node=" + this.config.nodeId());
      }
    } catch (WebsiteSyncApiException error) {
      handleApiFailure(error, this.heartbeatBackoff, true);
    } catch (IOException error) {
      logHeartbeatFailure("credential_persist_failed", error);
      handleFailure("credential_persist_failed", this.heartbeatBackoff.next(), true);
    } catch (RuntimeException error) {
      logHeartbeatFailure("snapshot_failed", error);
      handleFailure("snapshot_failed", this.heartbeatBackoff.next(), true);
    }
  }

  private void scheduleTextureTick(Duration delay) {
    if (this.state.get() != State.ACTIVE || !this.config.textures().enabled()
        || this.texturesInFlight.get()) return;
    try {
      synchronized (this.textureScheduleLock) {
        if (this.textureFuture != null) this.textureFuture.cancel(false);
        this.textureFuture = this.scheduler.schedule(this::runTextureSchedule,
            Math.max(1L, delay.toMillis()), TimeUnit.MILLISECONDS);
      }
    } catch (RejectedExecutionException error) {
      if (this.state.get() != State.STOPPED) {
        this.logger.accept("StarX website texture scheduler rejected node=" + this.config.nodeId());
      }
    }
  }

  private void runTextureSchedule() {
    synchronized (this.textureScheduleLock) {
      this.textureFuture = null;
    }
    if (this.state.get() != State.ACTIVE || !this.config.textures().enabled()) return;
    if (!mayRun(this.nextTextureMillis)) {
      scheduleTextureTick(nextTextureDelay());
      return;
    }
    try {
      textureTick();
    } catch (RuntimeException error) {
      handleFailure("texture_scheduler_failed", this.textureBackoff.next(), false);
      this.logger.accept("StarX website texture scheduler recovered: node="
          + this.config.nodeId() + " error=" + error.getClass().getSimpleName());
    }
  }

  private Duration nextTextureDelay() {
    if (!this.config.textures().enabled() || this.state.get() != State.ACTIVE) {
      return Duration.ofSeconds(5);
    }
    long remaining = this.nextTextureMillis - this.clock.millis();
    return Duration.ofMillis(Math.max(1_000L, remaining));
  }

  private void textureTick() {
    if (!this.config.textures().enabled()
        || this.state.get() != State.ACTIVE
        || !mayRun(this.nextTextureMillis)
        || !this.texturesInFlight.compareAndSet(false, true)) {
      return;
    }
    boolean accepted = submit(() -> {
      try {
        synchronizeTextures();
      } finally {
        this.texturesInFlight.set(false);
        scheduleTextureTick(nextTextureDelay());
      }
    }, this.texturesInFlight);
    if (!accepted) {
      this.nextTextureMillis = this.clock.millis() + Duration.ofSeconds(15).toMillis();
      scheduleTextureTick(nextTextureDelay());
    }
  }

  private void synchronizeTextures() {
    try {
      List<PlayerTextureRecord> records = new ArrayList<>(this.textureSource.snapshot());
      records.sort(Comparator.comparing(record -> record.manifest().playerUuid()));
      Map<String, TextureBlob> blobs = new HashMap<>();
      int batchSize = this.config.textures().batchSize();
      int pages = Math.max(1, (records.size() + batchSize - 1) / batchSize);
      String syncId = java.util.UUID.randomUUID().toString();
      Map<String, String> currentManifest = manifestFingerprints(records);
      boolean manifestUnchanged = currentManifest.equals(this.lastTextureManifest);
      Set<String> uploadedThisSync = new HashSet<>();
      for (int page = 0; page < pages; page++) {
        int offset = page * batchSize;
        int end = Math.min(records.size(), offset + batchSize);
        List<PlayerTextureRecord> pageRecords = records.subList(offset, end);
        List<PlayerTexture> batch = pageRecords.stream()
            .map(PlayerTextureRecord::manifest)
            .toList();
        if (manifestUnchanged) {
          continue;
        }
        ManifestAck manifest = this.client.submitManifestPage(
            this.nodeToken.get(), syncId, page, pages, batch);
        for (PlayerTextureRecord record : pageRecords) {
          record.blob(TextureKind.SKIN).ifPresent(blob -> blobs.put(blob.sha256(), blob));
          record.blob(TextureKind.CAPE).ifPresent(blob -> blobs.put(blob.sha256(), blob));
        }
        for (MissingTexture missing : manifest.missingHashes()) {
          if (this.rejectedTextureHashes.contains(missing.hash())
              || !uploadedThisSync.add(missing.hash())) {
            continue;
          }
          TextureBlob blob = blobs.get(missing.hash());
          if (blob == null || blob.kind() != missing.kind()) {
            continue;
          }
          try {
            this.client.uploadTexture(this.nodeToken.get(), blob);
          } catch (WebsiteSyncApiException error) {
            if (error.unauthorized()) {
              authFailed(error.errorCode());
              return;
            }
            switch (error.errorCode()) {
              case "texture_not_requested" -> {
                this.nextTextureMillis = 0;
                return;
              }
              case "dimensions_invalid", "texture_too_large" -> {
                this.rejectedTextureHashes.add(missing.hash());
                this.logger.accept(
                    "StarX texture skipped: hash=" + missing.hash()
                        + " code=" + error.errorCode());
              }
              case "hash_mismatch" -> {
                this.nextTextureMillis = 0;
                return;
              }
              default -> throw error;
            }
          }
        }
      }
      this.lastTextureManifest = Map.copyOf(currentManifest);
      this.lastTextureSync = this.clock.instant();
      this.textureBackoff.reset();
      this.nextTextureMillis = this.clock.millis()
          + this.config.textures().manifestInterval().toMillis();
    } catch (WebsiteSyncApiException error) {
      handleApiFailure(error, this.textureBackoff, false);
    } catch (Exception error) {
      handleFailure("texture_source_failed", this.textureBackoff.next(), false);
    }
  }

  static Map<String, String> manifestFingerprints(
      List<PlayerTextureRecord> records
  ) {
    Map<String, String> fingerprints = new HashMap<>(records.size());
    for (PlayerTextureRecord record : records) {
      PlayerTexture manifest = record.manifest();
      fingerprints.put(manifest.playerUuid(), String.join("|",
          manifest.playerName(),
          Objects.toString(manifest.skinHash(), ""),
          Objects.toString(manifest.capeHash(), ""),
          manifest.model(),
          manifest.source(),
          manifest.updatedAt(),
          Boolean.toString(manifest.deleted())));
    }
    return Map.copyOf(fingerprints);
  }

  private void handleApiFailure(
      WebsiteSyncApiException error,
      ExponentialBackoff backoff,
      boolean heartbeat
  ) {
    if (error.unauthorized()) {
      authFailed(error.errorCode());
      return;
    }
    if (heartbeat) {
      logHeartbeatFailure(error.errorCode(), error);
    }
    handleFailure(error.errorCode(), backoff.next(), heartbeat);
  }

  private void logHeartbeatFailure(String code, Exception error) {
    this.logger.accept(
        "StarX website heartbeat failed: node=" + this.config.nodeId()
            + " code=" + code + " error=" + error.getClass().getSimpleName());
  }

  private void authFailed(String code) {
    this.lastErrorCode = code;
    transitionTo(State.AUTH_FAILED);
    this.nextHeartbeatMillis = Long.MAX_VALUE;
    this.nextTextureMillis = Long.MAX_VALUE;
    this.logger.accept(
        "StarX website credential was rejected; synchronization stopped. Re-enroll node="
            + this.config.nodeId());
  }

  private void handleFailure(String code, Duration delay, boolean heartbeat) {
    this.lastErrorCode = code;
    transitionTo(State.BACKOFF);
    long due = this.clock.millis() + delay.toMillis();
    if (heartbeat) {
      this.nextHeartbeatMillis = due;
    } else {
      this.nextTextureMillis = due;
    }
  }

  private boolean mayRun(long dueMillis) {
    State current = this.state.get();
    return current != State.DISABLED
        && current != State.AUTH_FAILED
        && current != State.STOPPED
        && this.clock.millis() >= dueMillis;
  }

  private boolean submit(Runnable task, AtomicBoolean guard) {
    try {
      this.workers.execute(() -> {
        try {
          task.run();
        } finally {
          recoverWorkerQueuePressure();
        }
      });
      return true;
    } catch (RejectedExecutionException error) {
      guard.set(false);
      this.lastErrorCode = "queue_full";
      if (this.workerQueueSaturated.compareAndSet(false, true)) {
        publishHealthChange("worker-queue", "NORMAL", "SATURATED", "queue_full");
      }
      return false;
    }
  }

  private void recoverWorkerQueuePressure() {
    if (this.workerQueueSaturated.get()
        && this.workers.getQueue().size() <= WORKER_QUEUE_CAPACITY / 2
        && this.workerQueueSaturated.compareAndSet(true, false)) {
      publishHealthChange("worker-queue", "SATURATED", "NORMAL", "");
    }
  }

  private void transitionTo(State next) {
    State previous = this.state.getAndSet(Objects.requireNonNull(next, "next"));
    if (previous != next) {
      publishHealthChange("runtime", previous.name(), next.name(), this.lastErrorCode);
    }
  }

  private void publishHealthChange(
      String component,
      String previousState,
      String nextState,
      String errorCode) {
    try {
      this.healthChangeListener.accept(new HealthChange(
          component,
          previousState,
          nextState,
          errorCode == null ? "" : errorCode,
          this.workers.getQueue().size(),
          this.rejectedWorkerTasks.get(),
          this.clock.instant()));
    } catch (RuntimeException error) {
      this.logger.accept("Website health listener failed: " + error.getClass().getSimpleName());
    }
  }

  @Override
  public void close() {
    State previous = this.state.get();
    if (previous == State.STOPPED) {
      return;
    }
    transitionTo(State.STOPPED);
    this.scheduler.shutdownNow();
    NodeSnapshot snapshot = this.latestSnapshot;
    SecretValue token = this.nodeToken.get();
    if (this.config.enabled() && token.isPresent() && snapshot != null) {
      Thread finalizer = daemonFactory("starx-website-final-heartbeat").newThread(() -> {
        try {
          this.client.heartbeat(
              token,
              this.config.nodeId(),
              this.capabilities,
              snapshot.offline());
        } catch (WebsiteSyncApiException ignored) {
          // Best effort only; shutdown must never wait for the website.
        }
      });
      finalizer.start();
    }
    this.workers.shutdownNow();
  }

  private static ThreadFactory daemonFactory(String prefix) {
    java.util.concurrent.atomic.AtomicInteger sequence =
        new java.util.concurrent.atomic.AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
