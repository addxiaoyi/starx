package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.TimeUnit;

/** Coordinates player transfers so every entry point shares one in-flight request. */
public final class TransferCoordinator {
  private static final int MAX_ACTIVE_PER_TARGET = 32;
  private final Duration timeout;
  private final ConcurrentMap<UUID, CompletableFuture<Result>> active = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, CompletableFuture<com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result>>
      transport = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, String> activeTargets = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, LongAdder> targetActive = new ConcurrentHashMap<>();
  private final LongAdder successful = new LongAdder();
  private final LongAdder rejected = new LongAdder();
  private final LongAdder failed = new LongAdder();
  private final LongAdder duplicate = new LongAdder();
  private final LongAdder cancelled = new LongAdder();
  private final ConcurrentLinkedDeque<Long> durationsMs = new ConcurrentLinkedDeque<>();

  public TransferCoordinator(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  public CompletionStage<Result> transfer(Player player, RegisteredServer target, String reason) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(target, "target");
    String transferReason = reason == null || reason.isBlank() ? "unspecified" : reason.trim();
    UUID playerId = player.getUniqueId();
    String targetName = target.getServerInfo().getName();
    long startedAt = System.nanoTime();
    CompletableFuture<Result> request = new CompletableFuture<>();
    CompletableFuture<Result> existing = this.active.putIfAbsent(playerId, request);
    if (existing != null) {
      this.duplicate.increment();
      return CompletableFuture.completedFuture(Result.duplicate(transferReason));
    }
    LongAdder targetCount = this.targetActive.computeIfAbsent(targetName, ignored -> new LongAdder());
    targetCount.increment();
    if (targetCount.sum() > MAX_ACTIVE_PER_TARGET) {
      targetCount.decrement();
      this.active.remove(playerId, request);
      this.rejected.increment();
      return CompletableFuture.completedFuture(Result.rejected("target-busy"));
    }
    this.activeTargets.put(playerId, targetName);
    try {
      CompletableFuture<com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result> connection =
          player.createConnectionRequest(target).connect();
      this.transport.put(playerId, connection);
      connection
          .orTimeout(this.timeout.toMillis(), TimeUnit.MILLISECONDS)
          .whenComplete((outcome, error) -> {
            this.active.remove(playerId, request);
            this.transport.remove(playerId, connection);
            releaseTarget(playerId);
            recordDuration(startedAt);
            if (error != null) {
              request.complete(Result.failure(transferReason, error));
              return;
            }
            boolean connected = outcome != null && outcome.isSuccessful();
            if (connected) {
              this.successful.increment();
              request.complete(Result.success(transferReason));
            } else {
              this.rejected.increment();
              request.complete(Result.rejected(transferReason));
            }
          });
    } catch (RuntimeException error) {
      this.active.remove(playerId, request);
      releaseTarget(playerId);
      recordDuration(startedAt);
      this.failed.increment();
      request.complete(Result.failure(transferReason, error));
    }
    return request;
  }

  public boolean cancel(UUID playerId) {
    if (playerId == null) return false;
    CompletableFuture<Result> request = this.active.remove(playerId);
    CompletableFuture<com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result> connection =
        this.transport.remove(playerId);
    if (connection != null) connection.cancel(true);
    releaseTarget(playerId);
    boolean completed = request != null && request.complete(Result.cancelled("disconnect"));
    if (completed) this.cancelled.increment();
    return completed;
  }

  public boolean isActive(UUID playerId) {
    return playerId != null && this.active.containsKey(playerId);
  }

  public int activeCount() {
    return this.active.size();
  }

  public void clear() {
    this.active.forEach((playerId, request) -> request.complete(Result.cancelled("shutdown")));
    this.cancelled.add(this.active.size());
    this.active.clear();
    this.transport.values().forEach(connection -> connection.cancel(true));
    this.transport.clear();
    this.activeTargets.clear();
    this.targetActive.clear();
  }

  public Snapshot snapshot() {
    long completed = this.successful.sum() + this.rejected.sum() + this.failed.sum();
    long successRate = completed == 0 ? -1L : Math.round(this.successful.sum() * 10000.0d / completed) / 100L;
    return new Snapshot(this.active.size(), this.successful.sum(), this.rejected.sum(),
        this.failed.sum(), this.duplicate.sum(), this.cancelled.sum(),
        percentile(50), percentile(95), percentile(99), successRate);
  }

  private void releaseTarget(UUID playerId) {
    String target = this.activeTargets.remove(playerId);
    if (target == null) return;
    this.targetActive.computeIfPresent(target, (ignored, count) -> {
      count.decrement();
      return count.sum() == 0 ? null : count;
    });
  }

  private void recordDuration(long startedAt) {
    this.durationsMs.addLast(Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L));
    while (this.durationsMs.size() > 256) this.durationsMs.pollFirst();
  }

  private long percentile(int percentile) {
    if (this.durationsMs.isEmpty()) return -1L;
    return this.durationsMs.stream().sorted()
        .skip(Math.max(0, (long) Math.ceil(this.durationsMs.size() * percentile / 100.0d) - 1L))
        .findFirst().orElse(-1L);
  }

  public record Snapshot(
      int active, long successful, long rejected, long failed, long duplicate,
      long cancelled, long latencyP50Ms, long latencyP95Ms, long latencyP99Ms,
      long successRatePercent) { }

  public record Result(Status status, String reason, Throwable error) {
    public boolean successful() {
      return this.status == Status.SUCCESS;
    }

    static Result success(String reason) {
      return new Result(Status.SUCCESS, reason, null);
    }

    static Result rejected(String reason) {
      return new Result(Status.REJECTED, reason, null);
    }

    static Result duplicate(String reason) {
      return new Result(Status.DUPLICATE, reason, null);
    }

    static Result cancelled(String reason) {
      return new Result(Status.CANCELLED, reason, null);
    }

    static Result failure(String reason, Throwable error) {
      return new Result(Status.FAILURE, reason, error);
    }
  }

  public enum Status {
    SUCCESS, REJECTED, DUPLICATE, CANCELLED, FAILURE
  }
}
