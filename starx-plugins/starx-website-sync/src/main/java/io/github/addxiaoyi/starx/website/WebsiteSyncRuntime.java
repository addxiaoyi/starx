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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

  private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
  private final WebsiteSyncConfig config;
  private final WebsiteSyncClient client;
  private final WebsiteSyncCredentialStore credentials;
  private final Supplier<NodeSnapshot> snapshotSupplier;
  private final TextureSource textureSource;
  private final List<String> capabilities;
  private final Consumer<String> logger;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final ThreadPoolExecutor workers;
  private final AtomicReference<SecretValue> nodeToken;
  private final AtomicReference<State> state;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean heartbeatInFlight = new AtomicBoolean();
  private final AtomicBoolean texturesInFlight = new AtomicBoolean();
  private final ExponentialBackoff heartbeatBackoff;
  private final ExponentialBackoff textureBackoff;
  private final Set<String> rejectedTextureHashes = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private volatile long nextHeartbeatMillis;
  private volatile long nextTextureMillis;
  private volatile Instant lastHeartbeat;
  private volatile Instant lastTextureSync;
  private volatile String lastErrorCode = "";
  private volatile NodeSnapshot latestSnapshot;

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
    this.scheduler = new ScheduledThreadPoolExecutor(2, daemonFactory("starx-website-schedule"));
    this.workers = new ThreadPoolExecutor(
        2,
        2,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32),
        daemonFactory("starx-website-http"),
        new ThreadPoolExecutor.AbortPolicy());
  }

  public void start() {
    if (!this.config.enabled() || !this.started.compareAndSet(false, true)) {
      return;
    }
    long now = this.clock.millis();
    this.nextHeartbeatMillis = now;
    this.nextTextureMillis = now;
    scheduleHeartbeat(Duration.ZERO);
    this.scheduler.scheduleWithFixedDelay(this::textureTick, 2, 2, TimeUnit.SECONDS);
  }

  public void forceHeartbeat() {
    if (this.state.get() != State.AUTH_FAILED && this.state.get() != State.STOPPED) {
      this.nextHeartbeatMillis = 0;
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

  private void heartbeatTick() {
    if (!mayRun(this.nextHeartbeatMillis) || !this.heartbeatInFlight.compareAndSet(false, true)) {
      return;
    }
    submit(() -> {
      try {
        publishHeartbeat();
      } finally {
        this.heartbeatInFlight.set(false);
      }
    }, this.heartbeatInFlight);
  }

  private void scheduleHeartbeat(Duration delay) {
    try {
      this.scheduler.schedule(this::runHeartbeatSchedule, delay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException error) {
      if (this.state.get() != State.STOPPED) {
        this.logger.accept(
            "StarX website heartbeat scheduler rejected node=" + this.config.nodeId());
      }
    }
  }

  private void runHeartbeatSchedule() {
    try {
      heartbeatTick();
    } catch (RuntimeException error) {
      this.heartbeatInFlight.set(false);
      handleFailure("heartbeat_scheduler_failed", this.heartbeatBackoff.next(), true);
      this.logger.accept(
          "StarX website heartbeat scheduler recovered after runtime failure: node="
              + this.config.nodeId() + " error=" + error.getClass().getSimpleName());
    } finally {
      if (this.state.get() != State.STOPPED) {
        scheduleHeartbeat(Duration.ofSeconds(1));
      }
    }
  }

  private void publishHeartbeat() {
    SecretValue token = this.nodeToken.get();
    try {
      if (!token.isPresent()) {
        if (!this.config.bootstrapToken().isPresent()) {
          this.state.set(State.WAITING_FOR_CREDENTIALS);
          this.nextHeartbeatMillis = Long.MAX_VALUE;
          return;
        }
        this.state.set(State.ENROLLING);
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
      boolean recovered = this.state.getAndSet(State.ACTIVE) == State.BACKOFF;
      this.lastHeartbeat = this.clock.instant();
      this.lastErrorCode = "";
      this.heartbeatBackoff.reset();
      this.nextHeartbeatMillis = this.clock.millis() + this.config.heartbeat().interval().toMillis();
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

  private void textureTick() {
    if (!this.config.textures().enabled()
        || this.state.get() != State.ACTIVE
        || !mayRun(this.nextTextureMillis)
        || !this.texturesInFlight.compareAndSet(false, true)) {
      return;
    }
    submit(() -> {
      try {
        synchronizeTextures();
      } finally {
        this.texturesInFlight.set(false);
      }
    }, this.texturesInFlight);
  }

  private void synchronizeTextures() {
    try {
      List<PlayerTextureRecord> records = new ArrayList<>(this.textureSource.snapshot());
      records.sort(Comparator.comparing(record -> record.manifest().playerUuid()));
      Map<String, TextureBlob> blobs = new HashMap<>();
      for (PlayerTextureRecord record : records) {
        record.blob(TextureKind.SKIN).ifPresent(blob -> blobs.put(blob.sha256(), blob));
        record.blob(TextureKind.CAPE).ifPresent(blob -> blobs.put(blob.sha256(), blob));
      }
      int batchSize = this.config.textures().batchSize();
      int pages = Math.max(1, (records.size() + batchSize - 1) / batchSize);
      String syncId = java.util.UUID.randomUUID().toString();
      for (int page = 0; page < pages; page++) {
        int offset = page * batchSize;
        int end = Math.min(records.size(), offset + batchSize);
        List<PlayerTexture> batch = records.subList(offset, end).stream()
            .map(PlayerTextureRecord::manifest)
            .toList();
        ManifestAck manifest = this.client.submitManifestPage(
            this.nodeToken.get(), syncId, page, pages, batch);
        for (MissingTexture missing : manifest.missingHashes()) {
          if (this.rejectedTextureHashes.contains(missing.hash())) {
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
    this.state.set(State.AUTH_FAILED);
    this.nextHeartbeatMillis = Long.MAX_VALUE;
    this.nextTextureMillis = Long.MAX_VALUE;
    this.logger.accept(
        "StarX website credential was rejected; synchronization stopped. Re-enroll node="
            + this.config.nodeId());
  }

  private void handleFailure(String code, Duration delay, boolean heartbeat) {
    this.lastErrorCode = code;
    this.state.set(State.BACKOFF);
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

  private void submit(Runnable task, AtomicBoolean guard) {
    try {
      this.workers.execute(task);
    } catch (RejectedExecutionException error) {
      guard.set(false);
      this.lastErrorCode = "queue_full";
    }
  }

  @Override
  public void close() {
    State previous = this.state.getAndSet(State.STOPPED);
    if (previous == State.STOPPED) {
      return;
    }
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
