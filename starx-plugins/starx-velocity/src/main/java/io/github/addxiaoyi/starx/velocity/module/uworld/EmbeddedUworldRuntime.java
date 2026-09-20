package io.github.addxiaoyi.starx.velocity.module.uworld;

import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.Limbo;
import io.github.addxiaoyi.starx.LimboFactory;
import io.github.addxiaoyi.starx.chunk.VirtualWorld;
import io.github.addxiaoyi.starx.limbo.LimboAPI;
import io.github.addxiaoyi.starx.uworld.StarxUworldFactory;
import io.github.addxiaoyi.starx.uworld.UworldCreationException;
import io.github.addxiaoyi.starx.uworld.UworldEnterResult;
import io.github.addxiaoyi.starx.uworld.UworldEnterStatus;
import io.github.addxiaoyi.starx.uworld.UworldFlowHandler;
import io.github.addxiaoyi.starx.uworld.UworldFlowOptions;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldOutcome;
import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldPhase;
import io.github.addxiaoyi.starx.uworld.UworldRuntime;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import io.github.addxiaoyi.starx.uworld.UworldWorldGenerator;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;

final class EmbeddedUworldRuntime implements UworldRuntime {

  @FunctionalInterface
  interface TimeoutScheduler {
    Cancellable schedule(Duration delay, Runnable action);
  }

  @FunctionalInterface
  interface Cancellable {
    void cancel();
  }

  @FunctionalInterface
  interface EventLoopExecutor {
    void execute(Player player, Runnable action);
  }

  interface CorePlayerLifecycle {
    void prepare(Player player);

    void release(Player player);
  }

  private static final System.Logger LOG = System.getLogger(EmbeddedUworldRuntime.class.getName());

  private final LimboFactory factory;
  private final TimeoutScheduler scheduler;
  private final EventLoopExecutor eventLoop;
  private final CorePlayerLifecycle corePlayers;
  private final Runnable coreClose;
  private final UworldRegistry<Player, ManagedUworld, ManagedUworldSession> registry =
      new UworldRegistry<>();
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean ready = new AtomicBoolean(true);
  private final AtomicBoolean coreClosed = new AtomicBoolean();
  private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

  private int worldCreations;
  private CompletableFuture<Void> worldCreationsDrained =
      CompletableFuture.completedFuture(null);

  EmbeddedUworldRuntime(
      LimboFactory factory,
      TimeoutScheduler scheduler,
      EventLoopExecutor eventLoop,
      Runnable coreClose
  ) {
    this(factory, scheduler, eventLoop, corePlayerLifecycle(factory), coreClose);
  }

  EmbeddedUworldRuntime(
      LimboFactory factory,
      TimeoutScheduler scheduler,
      EventLoopExecutor eventLoop,
      CorePlayerLifecycle corePlayers,
      Runnable coreClose
  ) {
    this.factory = Objects.requireNonNull(factory, "factory");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.eventLoop = Objects.requireNonNull(eventLoop, "eventLoop");
    this.corePlayers = Objects.requireNonNull(corePlayers, "corePlayers");
    this.coreClose = Objects.requireNonNull(coreClose, "coreClose");
  }

  @Override
  public boolean isReady() {
    return this.ready.get() && !this.registry.isStopping();
  }

  @Override
  public UworldHandle createWorld(
      String owner,
      UworldSpec spec,
      UworldWorldGenerator generator
  ) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(generator, "generator");
    this.beginWorldCreation(owner, spec.name());
    try {
      VirtualWorld rawWorld = this.factory.createVirtualWorld(
          spec.dimension(),
          spec.spawnX(),
          spec.spawnY(),
          spec.spawnZ(),
          spec.yaw(),
          spec.pitch());
      UworldWorldEditorImpl editor = new UworldWorldEditorImpl(this.factory, rawWorld);
      try {
        generator.generate(editor);
      } catch (Exception error) {
        UworldCreationException wrapped = new UworldCreationException(
            owner,
            spec.name(),
            "world generation failed: " + error.getMessage());
        wrapped.initCause(error);
        throw wrapped;
      } finally {
        editor.seal();
      }

      Limbo limbo = this.factory.createLimbo(rawWorld)
          .setName(spec.name())
          .setReadTimeout(spec.readTimeoutMillis())
          .setWorldTime(spec.worldTime())
          .setGameMode(spec.gameMode())
          .setViewDistance(spec.viewDistance())
          .setSimulationDistance(spec.simulationDistance());
      ManagedUworld managed = new ManagedUworld(this, owner, spec, limbo);
      try {
        this.registry.registerWorld(owner, spec.name(), managed);
        return managed;
      } catch (RuntimeException error) {
        managed.markClosed();
        managed.dispose();
        throw error;
      }
    } finally {
      this.endWorldCreation();
    }
  }

  @Override
  public Optional<UworldFlowSession> session(Player player) {
    return this.registry.session(player).map(value -> value);
  }

  UworldEnterResult enter(
      ManagedUworld world,
      Player player,
      UworldFlowOptions options,
      UworldFlowHandler handler
  ) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(handler, "handler");
    ManagedUworldSession session = new ManagedUworldSession(this, world, player, options, handler);
    synchronized (world) {
      if (!world.isOpen()) {
        return rejected(UworldEnterStatus.WORLD_CLOSED, "Uworld is closed");
      }

      UworldRegistry.ClaimResult claim = this.registry.claim(player, session);
      if (claim == UworldRegistry.ClaimResult.RUNTIME_STOPPING) {
        return rejected(UworldEnterStatus.RUNTIME_STOPPING, "Uworld runtime is stopping");
      }
      if (claim == UworldRegistry.ClaimResult.PLAYER_BUSY) {
        return rejected(UworldEnterStatus.PLAYER_BUSY, "Player already has a Uworld flow");
      }

      try {
        session.startEnteringTimeout();
        this.corePlayers.prepare(player);
        world.limbo().spawnPlayer(player, session);
        return new UworldEnterResult.Accepted(session);
      } catch (RuntimeException error) {
        this.terminate(
            session,
            UworldOutcomeType.SPAWN_REJECTED,
            Component.text("无法进入 Uworld"),
            null,
            false);
        return rejected(UworldEnterStatus.SPAWN_REJECTED, "Unable to enter Uworld");
      }
    }
  }

  boolean allowsBackend(Player player, RegisteredServer target) {
    ManagedUworldSession session = this.registry.session(player).orElse(null);
    return session == null || session.state().allowsTarget(target);
  }

  void onPreConnect(ServerPreConnectEvent event) {
    ManagedUworldSession session = this.registry.session(event.getPlayer()).orElse(null);
    if (session == null) {
      return;
    }
    RegisteredServer target = event.getResult().getServer().orElse(null);
    if (session.state().allowsTarget(target)) {
      return;
    }

    event.setResult(ServerPreConnectEvent.ServerResult.denied());
    if (session.phase() == UworldPhase.TRANSFERRING) {
      this.terminate(
          session,
          UworldOutcomeType.WRONG_TARGET,
          Component.text("Uworld 转服被重定向到了错误服务器"),
          null,
          true);
    }
  }

  void onConnected(Player player, RegisteredServer server) {
    ManagedUworldSession session = this.registry.session(player).orElse(null);
    if (session == null) {
      return;
    }

    UworldSessionState.TargetConnectResult result = session.state().onConnected(server);
    if (result == UworldSessionState.TargetConnectResult.COMPLETED) {
      this.publishTerminal(
          session,
          UworldOutcomeType.TRANSFERRED,
          Component.empty(),
          server,
          false);
    } else if (result == UworldSessionState.TargetConnectResult.WRONG_TARGET) {
      this.publishTerminal(
          session,
          UworldOutcomeType.WRONG_TARGET,
          Component.text("连接到了非预期子服"),
          null,
          true);
    }
  }

  void onKick(Player player, Component reason) {
    this.registry.session(player).ifPresent(session -> this.terminate(
        session,
        UworldOutcomeType.KICKED,
        Objects.requireNonNull(reason, "reason"),
        null,
        true));
  }

  void onDisconnect(Player player) {
    ManagedUworldSession session = this.registry.session(player).orElse(null);
    if (session == null) {
      return;
    }
    ManagedUworldSession.ProxyDisconnect disconnect = session.recordProxyDisconnect();
    if (disconnect.outcome() != null) {
      this.finishTerminal(session, disconnect.outcome());
      return;
    }
    if (session.phase() == UworldPhase.CLOSED) {
      if (disconnect.disconnectFailed() || session.outcomeCompleted()) {
        this.releaseTerminalResources(session);
      }
      return;
    }
    this.terminate(
        session,
        UworldOutcomeType.DISCONNECTED,
        Component.text("玩家已断开连接"),
        null,
        false);
  }

  CompletionStage<Void> closeWorld(
      ManagedUworld world,
      Component reason,
      UworldOutcomeType outcomeType
  ) {
    List<ManagedUworldSession> sessions = new ArrayList<>();
    synchronized (world) {
      world.markClosed();
      for (ManagedUworldSession session : this.registry.sessions()) {
        if (session.managedWorld() == world) {
          sessions.add(session);
        }
      }
    }

    CloseFailures failures = new CloseFailures("Unable to close Uworld " + world.name());
    List<SessionCompletion> completions = new ArrayList<>();
    for (ManagedUworldSession session : sessions) {
      try {
        this.terminate(
            session,
            outcomeType,
            reason,
            null,
            true);
      } catch (RuntimeException error) {
        failures.addRetryable("Unable to terminate Uworld session", error);
      }
      try {
        completions.add(new SessionCompletion(
            session,
            session.completion().toCompletableFuture()));
      } catch (RuntimeException error) {
        failures.addRetryable("Unable to observe Uworld session completion", error);
      }
    }
    CompletableFuture<?>[] pending = completions.stream()
        .map(SessionCompletion::future)
        .toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(pending).handle((ignored, completionError) -> {
      for (SessionCompletion completion : completions) {
        try {
          completion.future().join();
        } catch (RuntimeException error) {
          if (completion.session().markCompletionFailureReported()) {
            failures.add("Uworld session completion failed", unwrap(error));
          }
        }
      }
      for (ManagedUworldSession session : sessions) {
        RuntimeException pendingFailure = session.takeResourceReleaseFailure();
        if (pendingFailure != null) {
          failures.addRetryable("Unable to release Uworld core player", pendingFailure);
          continue;
        }
        try {
          this.releaseSessionResources(session);
        } catch (RuntimeException error) {
          failures.addRetryable("Unable to release Uworld core player", error);
        }
      }

      boolean disposed = false;
      try {
        world.dispose();
        disposed = true;
      } catch (RuntimeException error) {
        failures.addRetryable("Unable to dispose Uworld", error);
      }
      if (disposed && !failures.retryRequired()) {
        this.registry.removeWorld(world.name(), world);
      }
      failures.throwIfAny();
      return null;
    });
  }

  CompletionStage<Void> closeAsync(Component reason) {
    Objects.requireNonNull(reason, "reason");
    while (true) {
      CompletableFuture<Void> existing = this.closeFuture.get();
      if (existing != null) {
        return existing;
      }
      CompletableFuture<Void> closing = new CompletableFuture<>();
      if (!this.closeFuture.compareAndSet(null, closing)) {
        continue;
      }
      CompletableFuture<Void> creationsDrained;
      synchronized (this.lifecycleLock) {
        this.ready.set(false);
        this.registry.beginStopping();
        creationsDrained = this.worldCreationsDrained;
      }
      creationsDrained.whenComplete((ignored, creationError) -> {
        try {
          this.closeRegisteredWorlds(reason, closing, creationError);
        } catch (RuntimeException error) {
          this.finishClose(closing, error);
        }
      });
      return closing;
    }
  }

  boolean terminate(
      ManagedUworldSession session,
      UworldOutcomeType type,
      Component reason,
      RegisteredServer target,
      boolean disconnect
  ) {
    if (!session.state().close(type)) {
      return false;
    }
    this.publishTerminal(session, type, reason, target, disconnect);
    return true;
  }

  boolean terminate(
      ManagedUworldSession session,
      UworldPhase expectedPhase,
      UworldOutcomeType type,
      Component reason,
      RegisteredServer target,
      boolean disconnect
  ) {
    if (!session.state().close(expectedPhase, type)) {
      return false;
    }
    this.publishTerminal(session, type, reason, target, disconnect);
    return true;
  }

  Cancellable schedule(Duration delay, Runnable action) {
    return this.scheduler.schedule(delay, action);
  }

  void execute(Player player, Runnable action) {
    this.eventLoop.execute(player, action);
  }

  void onDetached(ManagedUworldSession session) {
    // Limbo also emits this callback while handing a player to a backend.
    // Velocity DisconnectEvent is the only reliable physical-disconnect barrier.
  }

  @Override
  public int worldCount() {
    return this.registry.worldCount();
  }

  @Override
  public int sessionCount() {
    return this.registry.sessionCount();
  }

  private void beginWorldCreation(String owner, String name) {
    synchronized (this.lifecycleLock) {
      if (!this.ready.get() || this.registry.isStopping()) {
        throw new UworldCreationException(owner, name, "runtime is not accepting worlds");
      }
      if (this.worldCreations == 0) {
        this.worldCreationsDrained = new CompletableFuture<>();
      }
      this.worldCreations++;
    }
  }

  private void endWorldCreation() {
    CompletableFuture<Void> drained = null;
    synchronized (this.lifecycleLock) {
      this.worldCreations--;
      if (this.worldCreations == 0) {
        drained = this.worldCreationsDrained;
      }
    }
    if (drained != null) {
      drained.complete(null);
    }
  }

  private void closeRegisteredWorlds(
      Component reason,
      CompletableFuture<Void> closing,
      Throwable creationError
  ) {
    CloseFailures failures = new CloseFailures("Unable to close Uworld runtime");
    if (creationError != null) {
      failures.add("Uworld creation did not drain cleanly", unwrap(creationError));
    }
    List<CompletableFuture<Void>> worlds = new ArrayList<>();
    for (ManagedUworld world : this.registry.worlds()) {
      try {
        worlds.add(world.closeForRuntime(reason).toCompletableFuture());
      } catch (RuntimeException error) {
        failures.add("Unable to start registered Uworld close", error);
      }
    }
    CompletableFuture.allOf(worlds.toArray(CompletableFuture[]::new))
        .whenComplete((ignored, worldError) -> {
          for (CompletableFuture<Void> world : worlds) {
            try {
              world.join();
            } catch (RuntimeException error) {
              failures.add("Registered Uworld close failed", unwrap(error));
            }
          }
          if (failures.hasFailures()) {
            this.finishClose(closing, failures.failure());
            return;
          }
          try {
            this.closeCore();
            this.finishClose(closing, null);
          } catch (RuntimeException closeError) {
            this.finishClose(closing, closeError);
          }
        });
  }

  private void closeCore() {
    synchronized (this.coreClosed) {
      if (this.coreClosed.get()) {
        return;
      }
      this.coreClose.run();
      this.coreClosed.set(true);
    }
  }

  private void finishClose(CompletableFuture<Void> closing, Throwable error) {
    if (error == null) {
      closing.complete(null);
      return;
    }
    this.closeFuture.compareAndSet(closing, null);
    closing.completeExceptionally(error);
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static final class CloseFailures {
    private final String message;
    private IllegalStateException failure;
    private boolean retryRequired;

    private CloseFailures(String message) {
      this.message = message;
    }

    private void add(String resource, Throwable error) {
      if (this.failure == null) {
        this.failure = new IllegalStateException(this.message);
      }
      this.failure.addSuppressed(new IllegalStateException(resource, error));
    }

    private void addRetryable(String resource, Throwable error) {
      this.add(resource, error);
      this.retryRequired = true;
    }

    private boolean hasFailures() {
      return this.failure != null;
    }

    private IllegalStateException failure() {
      return this.failure;
    }

    private boolean retryRequired() {
      return this.retryRequired;
    }

    private void throwIfAny() {
      if (this.failure != null) {
        throw new CompletionException(this.failure);
      }
    }
  }

  private record SessionCompletion(
      ManagedUworldSession session,
      CompletableFuture<?> future
  ) {
  }

  private void publishTerminal(
      ManagedUworldSession session,
      UworldOutcomeType type,
      Component reason,
      RegisteredServer target,
      boolean disconnect
  ) {
    Runnable publish = () -> {
      try {
        session.cancelTimeout();
      } catch (RuntimeException error) {
        LOG.log(System.Logger.Level.ERROR, "Unable to cancel Uworld timeout", error);
      }
      UworldOutcome outcome = new UworldOutcome(type, reason, Optional.ofNullable(target));
      if (disconnect) {
        UworldOutcome ready = session.deferUntilProxyDisconnect(outcome);
        if (ready != null) {
          this.finishTerminal(session, ready);
          return;
        }
        try {
          session.player().disconnect(reason);
        } catch (RuntimeException error) {
          LOG.log(System.Logger.Level.ERROR, "Unable to disconnect Uworld player", error);
          if (session.markDisconnectFailed()) {
            this.releaseTerminalResources(session);
            session.completeExceptionally(error);
          }
        }
        return;
      }
      this.finishTerminal(session, outcome);
    };
    try {
      this.execute(session.player(), publish);
    } catch (RuntimeException error) {
      LOG.log(System.Logger.Level.ERROR,
          "Unable to dispatch Uworld terminal outcome to the player event loop", error);
      publish.run();
    }
  }

  private static UworldEnterResult.Rejected rejected(UworldEnterStatus status, String message) {
    return new UworldEnterResult.Rejected(status, Component.text(message));
  }

  static void logCallbackFailure(RuntimeException error) {
    LOG.log(System.Logger.Level.ERROR, "Uworld outcome callback failed", error);
  }

  private static CorePlayerLifecycle corePlayerLifecycle(LimboFactory factory) {
    Objects.requireNonNull(factory, "factory");
    if (!(factory instanceof LimboAPI core)) {
      return new CorePlayerLifecycle() {
        @Override
        public void prepare(Player player) {
        }

        @Override
        public void release(Player player) {
        }
      };
    }
    return new CorePlayerLifecycle() {
      @Override
      public void prepare(Player player) {
        if (core instanceof StarxUworldFactory uworld) {
          uworld.setInitialID(player, player.getUniqueId());
        }
      }

      @Override
      public void release(Player player) {
        core.unsetLimboJoined(player);
        core.removeLoginQueue(player);
        core.removeKickCallback(player);
        core.removeNextServer(player);
        core.removeInitialID(player);
      }
    };
  }

  private void finishTerminal(ManagedUworldSession session, UworldOutcome outcome) {
    this.releaseTerminalResources(session);
    session.completeOutcome(outcome);
  }

  private void releaseTerminalResources(ManagedUworldSession session) {
    try {
      this.releaseSessionResources(session);
    } catch (RuntimeException error) {
      session.rememberResourceReleaseFailure(error);
      LOG.log(System.Logger.Level.ERROR, "Unable to clear Uworld player state", error);
    }
  }

  private void releaseSessionResources(ManagedUworldSession session) {
    session.releaseOwnedResources(() -> {
      ManagedUworldSession current = this.registry.session(session.player()).orElse(null);
      if (current != session) {
        return;
      }
      this.corePlayers.release(session.player());
      if (!this.registry.release(session.player(), session)) {
        throw new IllegalStateException("Uworld session ownership changed during resource release");
      }
    });
  }
}
