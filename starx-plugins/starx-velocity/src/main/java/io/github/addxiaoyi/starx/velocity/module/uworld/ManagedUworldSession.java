package io.github.addxiaoyi.starx.velocity.module.uworld;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.Limbo;
import io.github.addxiaoyi.starx.LimboSessionHandler;
import io.github.addxiaoyi.starx.player.LimboPlayer;
import io.github.addxiaoyi.starx.limbo.protocol.packets.s2c.SetExperiencePacket;
import io.github.addxiaoyi.starx.uworld.UworldFlowHandler;
import io.github.addxiaoyi.starx.uworld.UworldFlowOptions;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldOutcome;
import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldPhase;
import io.github.addxiaoyi.starx.uworld.UworldTransferPlayer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;

final class ManagedUworldSession implements UworldFlowSession, LimboSessionHandler {

  record ProxyDisconnect(UworldOutcome outcome, boolean disconnectFailed) {
  }

  private final EmbeddedUworldRuntime runtime;
  private final ManagedUworld world;
  private final Player player;
  private final UworldFlowOptions options;
  private final UworldFlowHandler handler;
  private final UworldSessionState<RegisteredServer> state = new UworldSessionState<>(
      ManagedUworldSession::sameServer);
  private final CompletableFuture<UworldOutcome> completion = new CompletableFuture<>();
  private final Object terminalLock = new Object();
  private final Object timeoutLock = new Object();
  private final Object resourceLock = new Object();

  private UworldOutcome pendingDisconnectOutcome;
  private boolean proxyDisconnected;
  private boolean disconnectFailed;
  private EmbeddedUworldRuntime.Cancellable timeout;
  private volatile ScheduledFuture<?> countdown;
  private long timeoutGeneration;
  private RuntimeException resourceReleaseFailure;
  private boolean resourcesReleased;
  private boolean completionFailureReported;

  private volatile LimboPlayer limboPlayer;

  ManagedUworldSession(
      EmbeddedUworldRuntime runtime,
      ManagedUworld world,
      Player player,
      UworldFlowOptions options,
      UworldFlowHandler handler
  ) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.world = Objects.requireNonNull(world, "world");
    this.player = Objects.requireNonNull(player, "player");
    this.options = Objects.requireNonNull(options, "options");
    this.handler = Objects.requireNonNull(handler, "handler");
  }

  @Override
  public Player player() {
    return this.player;
  }

  @Override
  public UworldHandle world() {
    return this.world;
  }

  ManagedUworld managedWorld() {
    return this.world;
  }

  UworldSessionState<RegisteredServer> state() {
    return this.state;
  }

  @Override
  public UworldPhase phase() {
    return this.state.phase();
  }

  @Override
  public boolean complete(RegisteredServer target) {
    Objects.requireNonNull(target, "target");
    LimboPlayer current = this.limboPlayer;
    if (current == null || !this.state.beginTransfer(target)) {
      return false;
    }

    try {
      this.replaceTimeout(this.options.transferTimeout(), UworldPhase.TRANSFERRING,
          () -> this.runtime.terminate(
              this,
              UworldPhase.TRANSFERRING,
              UworldOutcomeType.TIMED_OUT,
              Component.text("Uworld 转服超时"),
              null,
              true));
      CompletionStage<UworldTransferPlayer.TransferResult> transfer;
      if (current instanceof UworldTransferPlayer observable) {
        transfer = observable.transferResultTo(target);
      } else {
        current.disconnect(target);
        transfer = CompletableFuture.completedFuture(
            UworldTransferPlayer.TransferResult.started());
      }
      transfer.whenComplete((result, error) -> {
        if (error == null && result != null
            && result.status() == UworldTransferPlayer.Status.STARTED) {
          return;
        }
        if (error == null && result != null
            && result.status() == UworldTransferPlayer.Status.KICKED) {
          this.runtime.terminate(
              this,
              UworldOutcomeType.KICKED,
              result.reason(),
              null,
              true);
          return;
        }
        this.runtime.terminate(
            this,
            UworldOutcomeType.FAILED,
            Component.text("无法连接 Uworld 目标服务器"),
            null,
            true);
      });
      return true;
    } catch (RuntimeException error) {
      this.runtime.terminate(
          this,
          UworldOutcomeType.FAILED,
          Component.text("无法启动 Uworld 转服"),
          null,
          true);
      return false;
    }
  }

  @Override
  public boolean fail(Component reason) {
    return this.runtime.terminate(
        this,
        UworldOutcomeType.FAILED,
        Objects.requireNonNull(reason, "reason"),
        null,
        true);
  }

  @Override
  public boolean cancel(Component reason) {
    return this.runtime.terminate(
        this,
        UworldOutcomeType.CANCELLED,
        Objects.requireNonNull(reason, "reason"),
        null,
        true);
  }

  @Override
  public CompletionStage<UworldOutcome> completion() {
    return this.completion;
  }

  @Override
  public void execute(Runnable action) {
    Objects.requireNonNull(action, "action");
    LimboPlayer current = this.limboPlayer;
    if (current != null && current.getScheduledExecutor() != null) {
      current.getScheduledExecutor().execute(action);
      return;
    }
    this.runtime.execute(this.player, action);
  }

  @Override
  public void onSpawn(Limbo server, LimboPlayer player) {
    if (!this.state.activate()) {
      player.disconnect();
      return;
    }
    this.limboPlayer = player;
    player.disableFalling();
    try {
      this.replaceTimeout(this.options.activeTimeout(), UworldPhase.ACTIVE,
          () -> this.runtime.terminate(
              this,
              UworldPhase.ACTIVE,
              UworldOutcomeType.TIMED_OUT,
              Component.text("Uworld 流程超时"),
              null,
              true));
      this.startCountdown(player, this.options.activeTimeout());
    } catch (RuntimeException error) {
      this.runtime.terminate(
          this,
          UworldOutcomeType.FAILED,
          Component.text("无法调度 Uworld 流程超时任务"),
          null,
          true);
      return;
    }
    this.invoke(() -> this.handler.onReady(this));
  }

  @Override
  public void onChat(String message) {
    this.invoke(() -> this.handler.onChat(this, message));
  }

  @Override
  public void onMove(double x, double y, double z) {
    this.invoke(() -> this.handler.onMove(this, x, y, z));
  }

  @Override
  public void onRotate(float yaw, float pitch) {
    this.invoke(() -> this.handler.onRotate(this, yaw, pitch));
  }

  @Override
  public void onGround(boolean onGround) {
    this.invoke(() -> this.handler.onGround(this, onGround));
  }

  @Override
  public void onTeleport(int teleportId) {
    this.invoke(() -> this.handler.onTeleport(this, teleportId));
  }

  @Override
  public void onGeneric(Object packet) {
    this.invoke(() -> this.handler.onGeneric(this, packet));
  }

  @Override
  public void onDisconnect() {
    this.runtime.onDetached(this);
  }

  void replaceTimeout(java.time.Duration delay, UworldPhase expectedPhase, Runnable action) {
    Objects.requireNonNull(delay, "delay");
    Objects.requireNonNull(expectedPhase, "expectedPhase");
    Objects.requireNonNull(action, "action");
    if (expectedPhase != UworldPhase.ACTIVE) {
      this.cancelCountdown();
    }

    EmbeddedUworldRuntime.Cancellable previous;
    long generation;
    synchronized (this.timeoutLock) {
      generation = ++this.timeoutGeneration;
      previous = this.timeout;
      this.timeout = null;
    }
    if (previous != null) {
      previous.cancel();
    }

    EmbeddedUworldRuntime.Cancellable scheduled = this.runtime.schedule(
        delay,
        () -> {
          if (this.claimTimeout(generation, expectedPhase)) {
            action.run();
          }
        });
    boolean current;
    synchronized (this.timeoutLock) {
      current = this.timeoutGeneration == generation && this.phase() == expectedPhase;
      if (current) {
        this.timeout = scheduled;
      }
    }
    if (!current) {
      scheduled.cancel();
    }
  }

  void startEnteringTimeout() {
    this.replaceTimeout(this.options.activeTimeout(), UworldPhase.ENTERING,
        () -> this.runtime.terminate(
            this,
            UworldPhase.ENTERING,
            UworldOutcomeType.TIMED_OUT,
            Component.text("进入 Uworld 超时"),
            null,
            true));
  }

  void cancelTimeout() {
    this.cancelCountdown();
    EmbeddedUworldRuntime.Cancellable task;
    synchronized (this.timeoutLock) {
      this.timeoutGeneration++;
      task = this.timeout;
      this.timeout = null;
    }
    if (task != null) {
      task.cancel();
    }
  }

  private void startCountdown(LimboPlayer current, java.time.Duration duration) {
    this.cancelCountdown();
    long totalSeconds = Math.max(1, (duration.toMillis() + 999) / 1000);
    AtomicLong remaining = new AtomicLong(totalSeconds);
    this.showCountdown(current, UworldCountdownFrame.at(totalSeconds, totalSeconds));
    if (current.getScheduledExecutor() == null) {
      return;
    }
    this.countdown = current.getScheduledExecutor().scheduleAtFixedRate(() -> {
      if (this.phase() != UworldPhase.ACTIVE) {
        this.cancelCountdown();
        return;
      }
      long seconds = remaining.decrementAndGet();
      this.showCountdown(current, UworldCountdownFrame.at(totalSeconds, seconds));
      if (seconds <= 0) {
        this.cancelCountdown();
      }
    }, 1, 1, TimeUnit.SECONDS);
  }

  private void showCountdown(LimboPlayer current, UworldCountdownFrame frame) {
    current.writePacketAndFlush(new SetExperiencePacket(
        frame.progress(), frame.level(), frame.level()));
    if (frame.playSound()) {
      current.writePacketAndFlush(UworldCountdownSound.packet(frame));
    }
  }

  private void cancelCountdown() {
    ScheduledFuture<?> task = this.countdown;
    this.countdown = null;
    if (task != null) {
      task.cancel(false);
    }
  }

  private boolean claimTimeout(long generation, UworldPhase expectedPhase) {
    synchronized (this.timeoutLock) {
      if (this.timeoutGeneration != generation || this.phase() != expectedPhase) {
        return false;
      }
      this.timeoutGeneration++;
      this.timeout = null;
      return true;
    }
  }

  void completeOutcome(UworldOutcome outcome) {
    if (!this.completion.complete(outcome)) {
      return;
    }
    try {
      this.handler.onOutcome(this, outcome);
    } catch (RuntimeException error) {
      EmbeddedUworldRuntime.logCallbackFailure(error);
    }
  }

  UworldOutcome deferUntilProxyDisconnect(UworldOutcome outcome) {
    Objects.requireNonNull(outcome, "outcome");
    synchronized (this.terminalLock) {
      if (this.pendingDisconnectOutcome != null) {
        throw new IllegalStateException("Uworld outcome is already pending disconnect");
      }
      if (this.proxyDisconnected) {
        return outcome;
      }
      this.pendingDisconnectOutcome = outcome;
      return null;
    }
  }

  ProxyDisconnect recordProxyDisconnect() {
    synchronized (this.terminalLock) {
      this.proxyDisconnected = true;
      UworldOutcome outcome = this.pendingDisconnectOutcome;
      this.pendingDisconnectOutcome = null;
      return new ProxyDisconnect(outcome, this.disconnectFailed);
    }
  }

  boolean markDisconnectFailed() {
    synchronized (this.terminalLock) {
      if (this.pendingDisconnectOutcome == null) {
        return false;
      }
      this.pendingDisconnectOutcome = null;
      this.disconnectFailed = true;
      return true;
    }
  }

  void completeExceptionally(Throwable error) {
    this.completion.completeExceptionally(Objects.requireNonNull(error, "error"));
  }

  boolean outcomeCompleted() {
    return this.completion.isDone();
  }

  void releaseOwnedResources(Runnable release) {
    Objects.requireNonNull(release, "release");
    synchronized (this.resourceLock) {
      if (this.resourcesReleased) {
        return;
      }
      release.run();
      this.resourcesReleased = true;
    }
  }

  void rememberResourceReleaseFailure(RuntimeException error) {
    Objects.requireNonNull(error, "error");
    synchronized (this.resourceLock) {
      if (this.resourceReleaseFailure == null) {
        this.resourceReleaseFailure = error;
      } else if (this.resourceReleaseFailure != error) {
        this.resourceReleaseFailure.addSuppressed(error);
      }
    }
  }

  RuntimeException takeResourceReleaseFailure() {
    synchronized (this.resourceLock) {
      RuntimeException failure = this.resourceReleaseFailure;
      this.resourceReleaseFailure = null;
      return failure;
    }
  }

  boolean markCompletionFailureReported() {
    synchronized (this.resourceLock) {
      if (this.completionFailureReported) {
        return false;
      }
      this.completionFailureReported = true;
      return true;
    }
  }

  private void invoke(Runnable callback) {
    if (this.phase() == UworldPhase.CLOSED) {
      return;
    }
    try {
      callback.run();
    } catch (RuntimeException error) {
      this.runtime.terminate(
          this,
          UworldOutcomeType.FAILED,
          Component.text("Uworld 流程回调失败"),
          null,
          true);
    }
  }

  private static boolean sameServer(RegisteredServer expected, RegisteredServer actual) {
    return expected != null && actual != null
        && expected.getServerInfo().getName().equals(actual.getServerInfo().getName());
  }
}
