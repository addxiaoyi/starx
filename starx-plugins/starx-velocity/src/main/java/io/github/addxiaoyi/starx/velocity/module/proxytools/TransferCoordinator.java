package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Owns one transfer per player across commands and queues. */
public final class TransferCoordinator implements AutoCloseable {
  private static final int MAX_ACTIVE_PER_TARGET = 32;
  private static final int DURATION_WINDOW = 256;
  private final Duration timeout;
  private final Object lock = new Object();
  private final Map<UUID, Attempt> active = new HashMap<>();
  private final Map<String, Integer> targetActive = new HashMap<>();
  private final ArrayDeque<Long> durationsMs = new ArrayDeque<>();
  private long successful;
  private long rejected;
  private long failed;
  private long duplicate;
  private long cancelled;
  private boolean closed;

  public TransferCoordinator(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.toMillis() < 1) {
      throw new IllegalArgumentException("timeout must be at least one millisecond");
    }
  }

  public CompletionStage<Result> transfer(Player player, RegisteredServer target, String reason) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(target, "target");
    String transferReason = reason == null || reason.isBlank() ? "unspecified" : reason.trim();
    UUID playerId = player.getUniqueId();
    String targetName = target.getServerInfo().getName();
    Attempt attempt = new Attempt(player, playerId, targetName, transferReason);
    synchronized (this.lock) {
      if (this.closed || !player.isActive()) {
        return CompletableFuture.completedFuture(Result.cancelled(this.closed ? "shutdown" : "disconnect"));
      }
      if (this.active.containsKey(playerId)) {
        this.duplicate++;
        return CompletableFuture.completedFuture(Result.duplicate(transferReason));
      }
      int targetCount = this.targetActive.getOrDefault(targetName, 0);
      if (targetCount >= MAX_ACTIVE_PER_TARGET) {
        this.rejected++;
        return CompletableFuture.completedFuture(Result.rejected("target-busy"));
      }
      this.targetActive.put(targetName, targetCount + 1);
      this.active.put(playerId, attempt);
    }
    try {
      CompletableFuture<ConnectionRequestBuilder.Result> connection =
          player.createConnectionRequest(target).connect();
      boolean finished;
      synchronized (this.lock) {
        finished = attempt.finished;
        if (!finished) attempt.connection = connection;
      }
      // Cancellation may win while Velocity is creating the connection future.
      if (finished) {
        connection.cancel(true);
      } else {
        connection.copy()
            .orTimeout(this.timeout.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((outcome, error) -> finish(attempt,
                error != null ? Result.failure(transferReason, error)
                    : outcome != null && outcome.isSuccessful()
                    ? Result.success(transferReason) : Result.rejected(transferReason)));
      }
    } catch (RuntimeException error) {
      finish(attempt, Result.failure(transferReason, error));
    }
    return attempt.request.minimalCompletionStage();
  }

  public boolean cancel(UUID playerId) {
    return cancel(playerId, null);
  }

  public boolean cancel(UUID playerId, String reason) {
    Attempt attempt;
    synchronized (this.lock) {
      attempt = this.active.get(playerId);
      if (attempt == null || reason != null && !reason.equals(attempt.reason)) return false;
    }
    return finish(attempt, Result.cancelled("cancelled"));
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    Player player = event.getPlayer();
    Attempt attempt;
    synchronized (this.lock) {
      attempt = this.active.get(player.getUniqueId());
      if (attempt == null || attempt.player != player) return;
    }
    finish(attempt, Result.cancelled("disconnect"));
  }

  public boolean isActive(UUID playerId) {
    synchronized (this.lock) {
      return this.active.containsKey(playerId);
    }
  }

  public Optional<String> activeTarget(UUID playerId) {
    synchronized (this.lock) {
      Attempt attempt = this.active.get(playerId);
      return attempt == null ? Optional.empty() : Optional.of(attempt.target);
    }
  }

  public int activeCount() {
    synchronized (this.lock) {
      return this.active.size();
    }
  }

  public void cancelReason(String reason) {
    List<Attempt> attempts;
    synchronized (this.lock) {
      attempts = this.active.values().stream().filter(attempt -> attempt.reason.equals(reason)).toList();
    }
    attempts.forEach(attempt -> finish(attempt, Result.cancelled("module-disabled")));
  }

  public void clear() {
    List<Attempt> attempts;
    synchronized (this.lock) {
      attempts = List.copyOf(this.active.values());
    }
    attempts.forEach(attempt -> finish(attempt, Result.cancelled("shutdown")));
  }

  @Override
  public void close() {
    synchronized (this.lock) {
      this.closed = true;
    }
    clear();
  }

  private boolean finish(Attempt attempt, Result outcome) {
    CompletableFuture<ConnectionRequestBuilder.Result> connection;
    synchronized (this.lock) {
      if (attempt.finished) return false;
      attempt.finished = true;
      this.active.remove(attempt.playerId, attempt);
      this.targetActive.computeIfPresent(attempt.target, (target, count) -> count == 1 ? null : count - 1);
      switch (outcome.status()) {
        case SUCCESS -> this.successful++;
        case REJECTED -> this.rejected++;
        case FAILURE -> this.failed++;
        case CANCELLED -> this.cancelled++;
        case DUPLICATE -> throw new IllegalStateException("An admitted transfer cannot be duplicate");
      }
      this.durationsMs.addLast(Math.max(0L, (System.nanoTime() - attempt.startedAt) / 1_000_000L));
      if (this.durationsMs.size() > DURATION_WINDOW) this.durationsMs.removeFirst();
      connection = attempt.connection;
    }
    // Mark completion before cancellation invokes callbacks; publish outside the state lock.
    if (connection != null && (outcome.status() == Status.CANCELLED || outcome.status() == Status.FAILURE)) {
      connection.cancel(true);
    }
    attempt.request.complete(outcome);
    return true;
  }

  public Snapshot snapshot() {
    synchronized (this.lock) {
      long completed = this.successful + this.rejected + this.failed;
      long successRate = completed == 0 ? -1L : Math.round(this.successful * 100.0d / completed);
      long[] durations = this.durationsMs.stream().mapToLong(Long::longValue).toArray();
      Arrays.sort(durations);
      return new Snapshot(this.active.size(), this.successful, this.rejected, this.failed,
          this.duplicate, this.cancelled, percentile(durations, 50), percentile(durations, 95),
          percentile(durations, 99), successRate);
    }
  }

  private static long percentile(long[] durations, int percentile) {
    if (durations.length == 0) return -1L;
    return durations[(int) Math.ceil(durations.length * percentile / 100.0d) - 1];
  }

  private static final class Attempt {
    private final Player player;
    private final UUID playerId;
    private final String target;
    private final String reason;
    private final long startedAt = System.nanoTime();
    private final CompletableFuture<Result> request = new CompletableFuture<>();
    private CompletableFuture<ConnectionRequestBuilder.Result> connection;
    private boolean finished;

    private Attempt(Player player, UUID playerId, String target, String reason) {
      this.player = player;
      this.playerId = playerId;
      this.target = target;
      this.reason = reason;
    }
  }

  public record Snapshot(
      int active, long successful, long rejected, long failed, long duplicate,
      long cancelled, long latencyP50Ms, long latencyP95Ms, long latencyP99Ms,
      long successRatePercent) { }

  public record Result(Status status, String reason, Throwable error) {
    public boolean successful() { return this.status == Status.SUCCESS; }
    static Result success(String reason) { return new Result(Status.SUCCESS, reason, null); }
    static Result rejected(String reason) { return new Result(Status.REJECTED, reason, null); }
    static Result duplicate(String reason) { return new Result(Status.DUPLICATE, reason, null); }
    static Result cancelled(String reason) { return new Result(Status.CANCELLED, reason, null); }
    static Result failure(String reason, Throwable error) { return new Result(Status.FAILURE, reason, error); }
  }

  public enum Status { SUCCESS, REJECTED, DUPLICATE, CANCELLED, FAILURE }
}
